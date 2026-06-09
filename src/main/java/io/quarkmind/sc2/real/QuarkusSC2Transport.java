package io.quarkmind.sc2.real;

import SC2APIProtocol.Raw;
import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.Race;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.response.ResponseObservation;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Quarkus-native SC2 WebSocket transport — raw socket implementation.
 *
 * <p>Uses a plain {@link Socket} with manual WebSocket frame encoding (RFC 6455).
 * Avoids the JDK {@code java.net.http.WebSocket} API, which requires an HTTP
 * client layer that complicates testing and adds unnecessary overhead for a
 * simple binary framing protocol over a single local socket.
 *
 * <p>Thread safety invariant: {@link #sendSync} may only be called from the game
 * loop virtual thread. {@link #quit()} is safe from any thread — it sets a flag
 * and interrupts.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class QuarkusSC2Transport {

    private static final Logger log = Logger.getLogger(QuarkusSC2Transport.class);

    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    @ConfigProperty(name = "starcraft.sc2.port",                     defaultValue = "8168")
    int sc2Port;

    @ConfigProperty(name = "starcraft.sc2.map",                      defaultValue = "TorchesAIE_v4")
    String mapName;

    @ConfigProperty(name = "starcraft.sc2.difficulty",               defaultValue = "VERY_EASY")
    String difficultyStr;

    @ConfigProperty(name = "starcraft.sc2.ai.race",                  defaultValue = "RANDOM")
    String aiRaceStr;

    @ConfigProperty(name = "starcraft.sc2.race",                     defaultValue = "PROTOSS")
    String botRaceStr;

    @ConfigProperty(name = "starcraft.sc2.connect.retry",            defaultValue = "60")
    int connectRetryCount;

    @ConfigProperty(name = "starcraft.sc2.connect.retry-interval-ms", defaultValue = "5000")
    int connectRetryIntervalMs;

    // ---------------------------------------------------------------------------
    // Thread / lifecycle state
    // ---------------------------------------------------------------------------

    private volatile Thread gameLoopThread;
    private volatile boolean running = false;
    private final AtomicBoolean quitting = new AtomicBoolean(false);

    /** Package-private for testing — skip SC2 process launch when true. */
    boolean skipProcessLaunch = false;

    // ---------------------------------------------------------------------------
    // Socket / I/O state (owned by game loop virtual thread after connect())
    // ---------------------------------------------------------------------------

    private Socket socket;
    private OutputStream socketOut;

    /** One-in-flight: game loop polls, response reader puts. */
    private final SynchronousQueue<byte[]> responseQueue = new SynchronousQueue<>();

    /** Background reader thread that pulls frames off the socket. */
    private Thread readerThread;

    // ---------------------------------------------------------------------------
    // Public lifecycle
    // ---------------------------------------------------------------------------

    public void connect() {
        try {
            if (!skipProcessLaunch) launchSC2();
            tcpProbe(sc2Port);
            openSocket(sc2Port);
            performWebSocketHandshake(sc2Port);
            startFrameReader();
            Sc2Api.Response pong = sendSync(
                Sc2Api.Request.newBuilder().setPing(Sc2Api.RequestPing.getDefaultInstance()).build(),
                Duration.ofSeconds(30));
            log.infof("[SC2] Connected (status=%s)", pong.getStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("[SC2] connect() interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("[SC2] Failed to connect: " + e.getMessage(), e);
        }
    }

    /** Send {@code RequestCreateGame}. {@code realtime} NOT set — step-based control. */
    public void createGame() {
        Path mapFile = Path.of(System.getProperty("user.home"), ".quarkmind", "maps", mapName + ".SC2Map");
        Sc2Api.Request req = Sc2Api.Request.newBuilder()
            .setCreateGame(Sc2Api.RequestCreateGame.newBuilder()
                .setLocalMap(Sc2Api.LocalMap.newBuilder()
                    .setMapPath(mapFile.toAbsolutePath().toString()).build())
                .addPlayerSetup(Sc2Api.PlayerSetup.newBuilder()
                    .setType(Sc2Api.PlayerType.Participant).build())
                .addPlayerSetup(Sc2Api.PlayerSetup.newBuilder()
                    .setType(Sc2Api.PlayerType.Computer)
                    .setRace(Race.valueOf(aiRaceStr).toSc2Api())
                    .setDifficulty(Difficulty.valueOf(difficultyStr).toSc2Api()).build())
                .build())
            .build();
        try {
            sendSync(req, Duration.ofSeconds(60));
        } catch (Exception e) {
            throw new RuntimeException("[SC2] createGame() failed: " + e.getMessage(), e);
        }
    }

    /** Send {@code RequestJoinGame} with {@code InterfaceOptions.raw=true}. */
    public void joinGame() {
        Sc2Api.Request req = Sc2Api.Request.newBuilder()
            .setJoinGame(Sc2Api.RequestJoinGame.newBuilder()
                .setRace(Race.valueOf(botRaceStr).toSc2Api())
                .setOptions(Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
                .build())
            .build();
        try {
            sendSync(req, Duration.ofSeconds(60));
        } catch (Exception e) {
            throw new RuntimeException("[SC2] joinGame() failed: " + e.getMessage(), e);
        }
    }

    public void runGameLoop(SC2FrameCallback callback) {
        gameLoopThread = Thread.ofVirtual()
            .name("sc2-game-loop")
            .start(() -> gameLoop(callback));
    }

    public void quit() {
        quitting.set(true);
        Thread t = gameLoopThread;
        if (t != null) t.interrupt();
    }

    public boolean isRunning() { return running; }

    @PreDestroy
    public void shutdown() { quit(); }

    // ---------------------------------------------------------------------------
    // Game loop (virtual thread)
    // ---------------------------------------------------------------------------

    private void gameLoop(SC2FrameCallback callback) {
        running = true;
        boolean gameStarted = false;
        try {
            Sc2Api.Response infoResp = sendSync(
                Sc2Api.Request.newBuilder().setGameInfo(Sc2Api.RequestGameInfo.getDefaultInstance()).build(),
                Duration.ofSeconds(60));
            ResponseGameInfo gameInfo = ResponseGameInfo.from(infoResp);
            callback.onGameStart(gameInfo);
            gameStarted = true;

            while (!quitting.get() && !Thread.currentThread().isInterrupted()) {
                Sc2Api.Response obsResp = sendSync(
                    Sc2Api.Request.newBuilder()
                        .setObservation(Sc2Api.RequestObservation.getDefaultInstance()).build(),
                    Duration.ofSeconds(5));

                if (obsResp.getStatus() != Sc2Api.Status.in_game) break;

                ResponseObservation ro = ResponseObservation.from(obsResp);
                Observation obs = ro.getObservation();
                callback.onStep(obs);

                sendSync(Sc2Api.Request.newBuilder()
                    .setStep(Sc2Api.RequestStep.newBuilder().setCount(1).build()).build(),
                    Duration.ofSeconds(5));
            }
        } catch (InterruptedException e) {
            log.debug("[SC2] Game loop interrupted (quit requested)");
        } catch (Exception e) {
            log.errorf("[SC2] Game loop error: %s", e.getMessage());
        } finally {
            running = false;
            if (quitting.get()) {
                try {
                    sendSync(Sc2Api.Request.newBuilder()
                        .setQuit(Sc2Api.RequestQuit.getDefaultInstance()).build(),
                        Duration.ofSeconds(5));
                } catch (Exception ignored) {}
            }
            closeSocket();
            if (gameStarted) callback.onGameEnd();
            gameLoopThread = null;
        }
    }

    // ---------------------------------------------------------------------------
    // Called from SC2BotAgent.onStep() — game loop thread only
    // ---------------------------------------------------------------------------

    public void sendActions(List<ResolvedCommand> commands) throws InterruptedException {
        Sc2Api.RequestAction.Builder actionReq = Sc2Api.RequestAction.newBuilder();
        for (ResolvedCommand cmd : commands) {
            Raw.ActionRawUnitCommand.Builder unitCmd = Raw.ActionRawUnitCommand.newBuilder()
                .addUnitTags(cmd.tag().toSc2Api())
                .setAbilityId(cmd.ability().getAbilityId())
                .setQueueCommand(false);
            cmd.target().ifPresent(pos -> unitCmd.setTargetWorldSpacePos(pos.toSc2Api()));
            actionReq.addActions(Sc2Api.Action.newBuilder()
                .setActionRaw(Raw.ActionRaw.newBuilder().setUnitCommand(unitCmd.build()).build())
                .build());
        }
        try {
            sendSync(Sc2Api.Request.newBuilder().setAction(actionReq.build()).build(),
                Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[SC2] sendActions() failed: " + e.getMessage(), e);
        }
    }

    public void sendDebug(Sc2Api.RequestDebug req) throws InterruptedException {
        try {
            sendSync(Sc2Api.Request.newBuilder().setDebug(req).build(), Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[SC2] sendDebug() failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // sendSync — core I/O primitive (game loop thread only)
    // ---------------------------------------------------------------------------

    /**
     * Write a masked WebSocket binary frame and block until the response arrives.
     *
     * <p>Client→server frames are masked per RFC 6455 §5.3. Server→client frames
     * are unmasked. The {@link SynchronousQueue} enforces depth-0 — no stale response risk.
     *
     * <p>Thread-safety invariant: only ONE thread may call {@code sendSync} at a time.
     * During the lifecycle phase (connect/createGame/joinGame) the caller thread has exclusive
     * access. During the game loop, only the game loop virtual thread calls it. These two phases
     * do not overlap, so the invariant holds without explicit synchronization on the method.
     */
    private Sc2Api.Response sendSync(Sc2Api.Request request, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        byte[] payload = request.toByteArray();
        synchronized (socketOut) {
            socketOut.write(encodeClientFrame(payload));
            socketOut.flush();
        }
        byte[] responseBytes = responseQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (responseBytes == null)
            throw new TimeoutException("SC2 did not respond within " + timeout);
        Sc2Api.Response response = Sc2Api.Response.parseFrom(responseBytes);
        if (response.getErrorCount() > 0)
            throw new IOException("SC2 error: " + response.getErrorList());
        return response;
    }

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    /**
     * Encode a WebSocket binary frame with masking (client→server, RFC 6455 §5.3).
     * Handles all three length encodings: 1-byte (0–125), 2-byte (126–65535), 8-byte (65536+).
     */
    private static byte[] encodeClientFrame(byte[] payload) {
        byte[] mask = new byte[4];
        SECURE_RANDOM.nextBytes(mask); // RFC 6455 §10.3: mask must be unpredictable
        byte[] masked = payload.clone();
        for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];

        ByteArrayOutputStream frame = new ByteArrayOutputStream(10 + payload.length);
        frame.write(0x82); // FIN=1, opcode=2 (binary)
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long l = payload.length;
            for (int i = 7; i >= 0; i--) frame.write((int) ((l >> (8 * i)) & 0xFF));
        }
        frame.write(mask, 0, 4);
        frame.write(masked, 0, masked.length);
        return frame.toByteArray();
    }

    // ---------------------------------------------------------------------------
    // Background frame reader (separate virtual thread)
    // ---------------------------------------------------------------------------

    private void startFrameReader() {
        final InputStream in;
        try { in = socket.getInputStream(); } catch (IOException e) { return; }

        readerThread = Thread.ofVirtual().name("sc2-frame-reader").start(() -> {
            ByteArrayOutputStream msgBuf = new ByteArrayOutputStream();
            try {
                while (!socket.isClosed()) {
                    int b0 = in.read(); if (b0 < 0) break;
                    int b1 = in.read(); if (b1 < 0) break;
                    boolean masked = (b1 & 0x80) != 0;
                    int len = b1 & 0x7F;
                    if (len == 126) {
                        int h = in.read(), l = in.read();
                        if (h < 0 || l < 0) break;
                        len = (h << 8) | l;
                    } else if (len == 127) {
                        long llen = 0;
                        for (int i = 0; i < 8; i++) {
                            int b = in.read();
                            if (b < 0) { llen = -1; break; }
                            llen = (llen << 8) | b;
                        }
                        if (llen < 0) break;
                        len = (int) llen; // SC2 frames fit in int range
                    }
                    byte[] maskBytes = masked ? in.readNBytes(4) : new byte[0];
                    byte[] chunk = in.readNBytes(len);
                    if (masked) for (int i = 0; i < chunk.length; i++) chunk[i] ^= maskBytes[i % 4];

                    msgBuf.write(chunk);
                    boolean fin = (b0 & 0x80) != 0;
                    if (fin) {
                        byte[] complete = msgBuf.toByteArray();
                        msgBuf.reset();
                        try {
                            responseQueue.put(complete);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                if (!socket.isClosed()) log.debugf("[SC2] Frame reader closed: %s", e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // WebSocket handshake over raw socket (RFC 6455)
    // ---------------------------------------------------------------------------

    private void openSocket(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socketOut = socket.getOutputStream();
    }

    private void performWebSocketHandshake(int port) throws Exception {
        byte[] keyBytes = new byte[16];
        new java.util.Random().nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);

        String request = "GET /sc2api HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + wsKey + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        socketOut.write(request.getBytes());
        socketOut.flush();

        // Read HTTP 101 response — byte-by-byte to avoid consuming WebSocket frame data
        InputStream in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            int len = sb.length();
            if (len >= 4
                    && sb.charAt(len - 4) == '\r' && sb.charAt(len - 3) == '\n'
                    && sb.charAt(len - 2) == '\r' && sb.charAt(len - 1) == '\n') break;
        }
        if (!sb.toString().contains("101")) {
            throw new IOException("[SC2] WebSocket upgrade failed: " + sb.toString().split("\r\n")[0]);
        }
    }

    private void closeSocket() {
        if (readerThread != null) readerThread.interrupt();
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------------------------------------------------------------------
    // SC2 process launch and port polling
    // ---------------------------------------------------------------------------

    private void launchSC2() throws IOException {
        SC2Executable sc2 = resolveSC2Executable();
        log.infof("[SC2] Launching: %s", sc2.path());
        new ProcessBuilder(sc2.path().toString(),
            "-listen", "-port", String.valueOf(sc2Port), "-displayMode", "0").start();
    }

    private void tcpProbe(int port) throws InterruptedException {
        log.infof("[SC2] Waiting for port %d (%d retries × %dms)...",
            port, connectRetryCount, connectRetryIntervalMs);
        for (int i = 0; i < connectRetryCount; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                log.infof("[SC2] Port %d open (attempt %d)", port, i + 1);
                return;
            } catch (IOException ignored) {
                Thread.sleep(connectRetryIntervalMs);
            }
        }
        throw new RuntimeException("[SC2] Port " + port + " did not open after " + connectRetryCount + " attempts");
    }

    private static SC2Executable resolveSC2Executable() {
        Path info = Path.of(System.getProperty("user.home"),
            "Library", "Application Support", "Blizzard", "StarCraft II", "ExecuteInfo.txt");
        try {
            Path exe = Files.lines(info).findFirst()
                .map(l -> l.split("=", 2)).filter(p -> p.length == 2)
                .map(p -> Path.of(p[1].trim())).filter(Files::exists)
                .orElseThrow(() -> new RuntimeException("SC2 not found in " + info));
            String build = "BaseUnknown";
            for (int i = 0; i < exe.getNameCount(); i++) {
                String seg = exe.getName(i).toString();
                if (seg.startsWith("Base")) { build = seg; break; }
            }
            return new SC2Executable(exe, build);
        } catch (Exception e) {
            throw new RuntimeException("Cannot resolve SC2: " + e.getMessage(), e);
        }
    }

    private record SC2Executable(Path path, String baseBuild) {}
}
