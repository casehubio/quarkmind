package io.quarkmind.sc2.real;

import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.github.ocraft.s2client.protocol.Fixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Behavioral tests for QuarkusSC2Transport using a local FakeSC2Server.
 * No live SC2 binary required.
 *
 * Thread model under test: game loop runs on a virtual thread; test assertions
 * wait on CountDownLatches or blocking queues with short timeouts.
 */
class QuarkusSC2TransportTest {

    // ---------------------------------------------------------------------------
    // Fake SC2 server — speaks SC2's binary WebSocket proto protocol
    // ---------------------------------------------------------------------------

    static class FakeSC2Server implements Closeable {

        private final ServerSocket server;
        private final List<Sc2Api.Request> received = new CopyOnWriteArrayList<>();
        private volatile Socket client;
        private volatile Thread handler;

        // Configurable: how many in_game observation responses before returning ended
        volatile int observationsBeforeEnd = Integer.MAX_VALUE;
        // Configurable: if non-null, included in the ended ResponseObservation as a PlayerResult
        volatile Sc2Api.Result playerResultForGameEnd = null;
        private final AtomicInteger obsCount = new AtomicInteger();

        FakeSC2Server() throws IOException {
            server = new ServerSocket(0);
        }

        int port() { return server.getLocalPort(); }

        List<Sc2Api.Request> received() { return received; }

        void acceptAsync() {
            // Loop so the TCP probe (which connect+disconnects) doesn't consume the one accept().
            // Each connection attempt is handled in its own virtual thread; probe connections
            // fail during doHandshake() (EOF), are silently ignored, and the loop continues.
            handler = Thread.ofVirtual().name("fake-sc2-server-acceptor").start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket c = server.accept();
                        Thread.ofVirtual().name("fake-sc2-handler").start(() -> {
                            try {
                                doHandshake(c);
                                client = c; // mark as the active WebSocket client
                                serveFrames(c);
                            } catch (Exception ignored) {
                                try { c.close(); } catch (Exception ex2) {}
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        }

        private void doHandshake(Socket s) throws Exception {
            // Read HTTP headers byte-by-byte — avoids BufferedReader consuming
            // WebSocket frame data that serveFrames() needs to read later.
            InputStream rawIn = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = rawIn.read()) >= 0) {
                sb.append((char) b);
                int len = sb.length();
                if (len >= 4
                        && sb.charAt(len - 4) == '\r' && sb.charAt(len - 3) == '\n'
                        && sb.charAt(len - 2) == '\r' && sb.charAt(len - 1) == '\n') {
                    break; // end of HTTP headers (\r\n\r\n)
                }
            }
            String key = null;
            for (String line : sb.toString().split("\r\n")) {
                if (line.startsWith("Sec-WebSocket-Key:"))
                    key = line.substring("Sec-WebSocket-Key:".length()).trim();
            }
            String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n").getBytes());
            out.flush();
        }

        private void serveFrames(Socket s) throws Exception {
            var raw = s.getInputStream();
            var out = s.getOutputStream();
            while (!s.isClosed()) {
                int b0 = raw.read(); if (b0 < 0) break;
                int b1 = raw.read(); if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                int len = b1 & 0x7F;
                if (len == 126) len = (raw.read() << 8) | raw.read();
                byte[] mask  = masked ? raw.readNBytes(4) : new byte[0];
                byte[] payload = raw.readNBytes(len);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

                Sc2Api.Request req = Sc2Api.Request.parseFrom(payload);
                received.add(req);

                Sc2Api.Response resp = buildResponse(req);
                byte[] respBytes = resp.toByteArray();
                out.write(0x82); // FIN + binary
                if (respBytes.length < 126) out.write(respBytes.length);
                else { out.write(126); out.write((respBytes.length >> 8) & 0xFF); out.write(respBytes.length & 0xFF); }
                out.write(respBytes);
                out.flush();
            }
        }

        private Sc2Api.Response buildResponse(Sc2Api.Request req) {
            Sc2Api.Response.Builder b = Sc2Api.Response.newBuilder();
            if (req.hasPing())
                return b.setPing(Sc2Api.ResponsePing.getDefaultInstance())
                        .setStatus(Sc2Api.Status.launched).build();
            if (req.hasCreateGame())
                return b.setCreateGame(Sc2Api.ResponseCreateGame.getDefaultInstance())
                        .setStatus(Sc2Api.Status.init_game).build();
            if (req.hasJoinGame())
                return b.setJoinGame(Sc2Api.ResponseJoinGame.newBuilder().setPlayerId(1).build())
                        .setStatus(Sc2Api.Status.in_game).build();
            if (req.hasGameInfo()) {
                // Minimal ResponseGameInfo — no StartRaw (avoids large HEIGHT_MAP image data)
                // ocraft requires non-empty playersInfo so we include one PlayerInfo
                // race_requested is required by ocraft's PlayerInfo.from()
                Sc2Api.ResponseGameInfo gi = Sc2Api.ResponseGameInfo.newBuilder()
                    .setMapName("test-map")
                    .addPlayerInfo(Sc2Api.PlayerInfo.newBuilder()
                        .setPlayerId(1)
                        .setType(Sc2Api.PlayerType.Participant)
                        .setRaceRequested(SC2APIProtocol.Common.Race.Protoss)
                        .build())
                    .setOptions(Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
                    .build();
                return Sc2Api.Response.newBuilder()
                    .setGameInfo(gi).setStatus(Sc2Api.Status.in_game).build();
            }
            if (req.hasObservation()) {
                int n = obsCount.incrementAndGet();
                boolean isEnded = n > observationsBeforeEnd;
                Sc2Api.Status status = isEnded ? Sc2Api.Status.ended : Sc2Api.Status.in_game;
                // Minimal observation — avoids HEIGHT_MAP image data which makes frames
                // large enough to trigger the 2-byte frame length encoding.
                // All fields required by ocraft's ResponseObservation.from() are present.
                // 8bpp × 1×1 pixel = 1 byte required by ocraft ImageData.from() validation
                SC2APIProtocol.Common.ImageData emptyImg = SC2APIProtocol.Common.ImageData.newBuilder()
                    .setBitsPerPixel(8)
                    .setSize(SC2APIProtocol.Common.Size2DI.newBuilder().setX(1).setY(1).build())
                    .setData(com.google.protobuf.ByteString.copyFrom(new byte[]{0}))
                    .build();
                Sc2Api.ResponseObservation.Builder obsBuilder = Sc2Api.ResponseObservation.newBuilder()
                        .setObservation(Sc2Api.Observation.newBuilder()
                            .setGameLoop(n)
                            .setPlayerCommon(Sc2Api.PlayerCommon.newBuilder()
                                .setPlayerId(1).setMinerals(50).setVespene(25)
                                .setFoodCap(15).setFoodUsed(12).setFoodArmy(0)
                                .setFoodWorkers(12).setIdleWorkerCount(0).setArmyCount(0)
                                .build())
                            .setRawData(SC2APIProtocol.Raw.ObservationRaw.newBuilder()
                                .setPlayer(SC2APIProtocol.Raw.PlayerRaw.newBuilder()
                                    .setCamera(SC2APIProtocol.Common.Point.newBuilder()
                                        .setX(50).setY(50).build())
                                    .build())
                                .setMapState(SC2APIProtocol.Raw.MapState.newBuilder()
                                    .setVisibility(emptyImg)
                                    .setCreep(emptyImg)
                                    .build())
                                .build())
                            .build());
                if (isEnded && playerResultForGameEnd != null) {
                    obsBuilder.addPlayerResult(Sc2Api.PlayerResult.newBuilder()
                        .setPlayerId(1)
                        .setResult(playerResultForGameEnd)
                        .build());
                }
                return b.setObservation(obsBuilder.build()).setStatus(status).build();
            }
            if (req.hasStep())
                return b.setStep(Sc2Api.ResponseStep.getDefaultInstance())
                        .setStatus(Sc2Api.Status.in_game).build();
            if (req.hasAction())
                return b.setAction(Sc2Api.ResponseAction.getDefaultInstance())
                        .setStatus(Sc2Api.Status.in_game).build();
            if (req.hasDebug())
                return b.setDebug(Sc2Api.ResponseDebug.getDefaultInstance())
                        .setStatus(Sc2Api.Status.in_game).build();
            if (req.hasQuit())
                return b.setQuit(Sc2Api.ResponseQuit.getDefaultInstance())
                        .setStatus(Sc2Api.Status.quit).build();
            return b.setStatus(Sc2Api.Status.in_game).build();
        }

        /** Wait until we've received at least N requests from the client. */
        void awaitRequests(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (received.size() < count && System.currentTimeMillis() < deadline)
                Thread.sleep(10);
        }

        @Override public void close() throws IOException {
            server.close();
            if (client != null) { try { client.close(); } catch (IOException ignored) {} }
        }
    }

    // ---------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------

    /** Wire transport to a fake server, bypassing SC2 process launch. */
    private QuarkusSC2Transport connectedTransport(FakeSC2Server server) throws Exception {
        QuarkusSC2Transport t = new QuarkusSC2Transport();
        t.sc2Port              = server.port();
        t.mapName              = "test-map";
        t.difficultyStr        = "VERY_EASY";
        t.aiRaceStr            = "RANDOM";
        t.botRaceStr           = "PROTOSS";
        t.connectRetryCount    = 3;
        t.connectRetryIntervalMs = 100;
        t.skipProcessLaunch    = true;
        t.connect();
        return t;
    }

    private FakeSC2Server server;
    private QuarkusSC2Transport transport;

    @BeforeEach void setUp() throws IOException { server = new FakeSC2Server(); server.acceptAsync(); }
    @AfterEach  void tearDown() throws Exception { if (transport != null) transport.shutdown(); server.close(); }

    // ---------------------------------------------------------------------------
    // connect() — WebSocket + ping
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void connect_sendsPingAndSucceeds() throws Exception {
        transport = connectedTransport(server);
        server.awaitRequests(1, 5000);
        assertThat(server.received()).hasSize(1);
        assertThat(server.received().get(0).hasPing()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // createGame() — proto structure
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void createGame_sendsRequestCreateGame() throws Exception {
        transport = connectedTransport(server);
        transport.createGame();
        server.awaitRequests(2, 5000);
        Sc2Api.Request createReq = server.received().stream()
            .filter(Sc2Api.Request::hasCreateGame).findFirst().orElseThrow();
        assertThat(createReq.hasCreateGame()).isTrue();
    }

    @Test @Timeout(10)
    void createGame_doesNotSetRealtime() throws Exception {
        transport = connectedTransport(server);
        transport.createGame();
        server.awaitRequests(2, 5000);
        Sc2Api.RequestCreateGame cg = server.received().stream()
            .filter(Sc2Api.Request::hasCreateGame).findFirst().orElseThrow()
            .getCreateGame();
        assertThat(cg.hasRealtime()).as("realtime must NOT be set — step-based control").isFalse();
    }

    @Test @Timeout(10)
    void createGame_hasTwoPlayerSetups_ParticipantAndComputer() throws Exception {
        transport = connectedTransport(server);
        transport.createGame();
        server.awaitRequests(2, 5000);
        Sc2Api.RequestCreateGame cg = server.received().stream()
            .filter(Sc2Api.Request::hasCreateGame).findFirst().orElseThrow()
            .getCreateGame();
        assertThat(cg.getPlayerSetupCount()).isEqualTo(2);
        assertThat(cg.getPlayerSetup(0).getType()).isEqualTo(Sc2Api.PlayerType.Participant);
        assertThat(cg.getPlayerSetup(1).getType()).isEqualTo(Sc2Api.PlayerType.Computer);
    }

    @Test @Timeout(10)
    void createGame_botPlayerSetup_hasNoRaceField() throws Exception {
        // Proto comment: "Only used for a computer player" — bot PlayerSetup must NOT set race
        transport = connectedTransport(server);
        transport.createGame();
        server.awaitRequests(2, 5000);
        Sc2Api.PlayerSetup botSetup = server.received().stream()
            .filter(Sc2Api.Request::hasCreateGame).findFirst().orElseThrow()
            .getCreateGame().getPlayerSetup(0);
        assertThat(botSetup.hasRace()).as("bot PlayerSetup must not set race (proto comment)").isFalse();
    }

    // ---------------------------------------------------------------------------
    // joinGame() — InterfaceOptions.raw = true
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void joinGame_includesInterfaceOptionsRawTrue() throws Exception {
        transport = connectedTransport(server);
        transport.joinGame();
        server.awaitRequests(2, 5000);
        Sc2Api.RequestJoinGame jg = server.received().stream()
            .filter(Sc2Api.Request::hasJoinGame).findFirst().orElseThrow()
            .getJoinGame();
        assertThat(jg.hasOptions()).as("options must be set").isTrue();
        assertThat(jg.getOptions().getRaw()).as("InterfaceOptions.raw must be true").isTrue();
    }

    @Test @Timeout(10)
    void joinGame_setsRaceFromConfig() throws Exception {
        transport = connectedTransport(server);
        transport.joinGame();
        server.awaitRequests(2, 5000);
        Sc2Api.RequestJoinGame jg = server.received().stream()
            .filter(Sc2Api.Request::hasJoinGame).findFirst().orElseThrow()
            .getJoinGame();
        assertThat(jg.getRace()).isEqualTo(SC2APIProtocol.Common.Race.Protoss);
    }

    // ---------------------------------------------------------------------------
    // runGameLoop() — game loop behaviour
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void gameLoop_callsOnGameStart_thenOnStep_thenOnGameEnd() throws Exception {
        server.observationsBeforeEnd = 1; // one frame then end
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        CountDownLatch gameStarted = new CountDownLatch(1);
        CountDownLatch stepFired   = new CountDownLatch(1);
        CountDownLatch gameEnded   = new CountDownLatch(1);
        List<Observation> stepObs  = new CopyOnWriteArrayList<>();

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) { gameStarted.countDown(); }
            @Override public void onStep(Observation obs) throws InterruptedException { stepObs.add(obs); stepFired.countDown(); }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { gameEnded.countDown(); }
        });

        assertThat(gameStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(stepFired.await(5,   java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(gameEnded.await(5,   java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(stepObs).hasSize(1);
        assertThat(stepObs.get(0)).isNotNull();
    }

    @Test @Timeout(10)
    void gameLoop_sendsRequestStep_afterEachObservation() throws Exception {
        server.observationsBeforeEnd = 2; // two frames
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        CountDownLatch gameEnded = new CountDownLatch(1);
        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo i) {}
            @Override public void onStep(Observation obs) throws InterruptedException {}
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { gameEnded.countDown(); }
        });

        assertThat(gameEnded.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Expect: ping, createGame, joinGame, gameInfo, obs×2, step×2 + possibly quit
        long stepCount = server.received().stream().filter(Sc2Api.Request::hasStep).count();
        assertThat(stepCount).as("one RequestStep per observation frame").isGreaterThanOrEqualTo(2);
    }

    @Test @Timeout(10)
    void gameLoop_isRunning_trueWhileRunning_falseAfterEnd() throws Exception {
        server.observationsBeforeEnd = Integer.MAX_VALUE; // keep running until we quit
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        // Block inside onStep so we can assert isRunning() while the loop is alive.
        // Without blocking, the game loop can complete before the assertion runs.
        CountDownLatch stepStarted   = new CountDownLatch(1);
        CountDownLatch stepCanFinish = new CountDownLatch(1);
        CountDownLatch ended         = new CountDownLatch(1);
        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo i) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                stepStarted.countDown();
                stepCanFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { ended.countDown(); }
        });

        assertThat(stepStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(transport.isRunning()).as("running while blocked in onStep").isTrue();
        stepCanFinish.countDown(); // release onStep, then quit
        transport.quit();
        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(transport.isRunning()).as("not running after quit").isFalse();
    }

    // ---------------------------------------------------------------------------
    // quit() — threading invariants
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void quit_terminatesRunningGameLoop_andFiresOnGameEnd() throws Exception {
        server.observationsBeforeEnd = Integer.MAX_VALUE; // never ends naturally
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch ended   = new CountDownLatch(1);
        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo i) {}
            @Override public void onStep(Observation obs) throws InterruptedException { stepped.countDown(); }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { ended.countDown(); }
        });

        assertThat(stepped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("at least one step before quit").isTrue();
        transport.quit();
        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("onGameEnd fires after quit()").isTrue();
    }

    @Test @Timeout(10)
    void quit_duringOnStep_terminatesCleanly_withoutErrorState() throws Exception {
        // Verifies that InterruptedException propagates cleanly through sendActions() → onStep() →
        // game loop, so quit() during a pending send hits the interrupt catch path, not the
        // generic exception path (which would leave isRunning() in an ambiguous state).
        server.observationsBeforeEnd = Integer.MAX_VALUE;
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        CountDownLatch stepStarted = new CountDownLatch(1);
        CountDownLatch ended       = new CountDownLatch(1);
        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo i) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                stepStarted.countDown();
                // Block here so quit() interrupts us while inside onStep
                Thread.sleep(Long.MAX_VALUE);
            }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { ended.countDown(); }
        });

        assertThat(stepStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        transport.quit();
        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("onGameEnd fires after quit() interrupts onStep").isTrue();
        assertThat(transport.isRunning()).as("not running after clean termination").isFalse();
    }

    @Test @Timeout(10)
    void quit_sendsPingDoesNotThrow_whenNoLoopRunning() {
        transport = new QuarkusSC2Transport();
        assertThatNoException().isThrownBy(() -> transport.quit());
    }

    @Test
    void isRunning_returnsFalseInitially() {
        transport = new QuarkusSC2Transport();
        assertThat(transport.isRunning()).isFalse();
    }

    @Test
    void shutdown_doesNotThrow_whenNoLoopRunning() {
        transport = new QuarkusSC2Transport();
        assertThatNoException().isThrownBy(() -> transport.shutdown());
    }

    // ---------------------------------------------------------------------------
    // sendActions() — proto nesting (calls transport.sendActions from onStep)
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void sendActions_sendsRequestActionWithCorrectAbilityAndTag() throws Exception {
        server.observationsBeforeEnd = Integer.MAX_VALUE;
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        CountDownLatch actionSent = new CountDownLatch(1);
        CountDownLatch ended      = new CountDownLatch(1);
        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo i) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                // Build a command and send it via transport.sendActions()
                com.github.ocraft.s2client.protocol.unit.Tag tag =
                    com.github.ocraft.s2client.protocol.unit.Tag.of(42L);
                com.github.ocraft.s2client.protocol.data.Abilities ability =
                    com.github.ocraft.s2client.protocol.data.Abilities.ATTACK;
                transport.sendActions(List.of(new ResolvedCommand(tag, ability, java.util.Optional.empty())));
                actionSent.countDown();
                transport.quit();
            }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) { ended.countDown(); }
        });

        assertThat(actionSent.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Sc2Api.Request actionReq = server.received().stream()
            .filter(Sc2Api.Request::hasAction).findFirst().orElseThrow();
        Sc2Api.RequestAction ra = actionReq.getAction();
        assertThat(ra.getActionsCount()).isGreaterThan(0);
        SC2APIProtocol.Raw.ActionRawUnitCommand cmd =
            ra.getActions(0).getActionRaw().getUnitCommand();
        assertThat(cmd.getAbilityId()).isEqualTo(
            com.github.ocraft.s2client.protocol.data.Abilities.ATTACK.getAbilityId());
        assertThat(cmd.getUnitTagsList()).contains(42L);
        assertThat(cmd.hasTargetWorldSpacePos()).isFalse(); // no target for ATTACK without position
    }

    // ---------------------------------------------------------------------------
    // Fragment accumulation — FIN=0 + FIN=1 WebSocket fragmentation
    // ---------------------------------------------------------------------------

    /**
     * Standalone mini-server that sends the ping response as two WebSocket fragments
     * (FIN=0 binary frame then FIN=1 continuation frame) to exercise the frame reader's
     * accumulation path. Separate from FakeSC2Server because serveFrames() is private.
     */
    static class FragmentingPingServer implements Closeable {
        private final ServerSocket server;
        FragmentingPingServer() throws IOException { server = new ServerSocket(0); }
        int port() { return server.getLocalPort(); }

        void acceptAsync() {
            Thread.ofVirtual().name("frag-server").start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket c = server.accept();
                        Thread.ofVirtual().start(() -> {
                            try { handle(c); } catch (Exception ignored) {}
                        });
                    }
                } catch (Exception ignored) {}
            });
        }

        private void handle(Socket s) throws Exception {
            // Handshake (same as FakeSC2Server.doHandshake)
            InputStream rawIn = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = rawIn.read()) >= 0) {
                sb.append((char) b);
                int len = sb.length();
                if (len >= 4 && sb.charAt(len-4)=='\r' && sb.charAt(len-3)=='\n'
                        && sb.charAt(len-2)=='\r' && sb.charAt(len-1)=='\n') break;
            }
            String key = null;
            for (String line : sb.toString().split("\r\n"))
                if (line.startsWith("Sec-WebSocket-Key:"))
                    key = line.substring("Sec-WebSocket-Key:".length()).trim();
            String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes());
            out.flush();

            // Read ping frame
            int b0 = rawIn.read(); int b1 = rawIn.read();
            boolean masked = (b1 & 0x80) != 0;
            int len = b1 & 0x7F;
            byte[] mask = masked ? rawIn.readNBytes(4) : new byte[0];
            byte[] payload = rawIn.readNBytes(len);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

            // Build ping response
            Sc2Api.Response resp = Sc2Api.Response.newBuilder()
                .setPing(Sc2Api.ResponsePing.getDefaultInstance())
                .setStatus(Sc2Api.Status.launched).build();
            byte[] respBytes = resp.toByteArray();

            // Send as TWO fragments: FIN=0 binary (part1) + FIN=1 continuation (part2)
            int half = Math.max(1, respBytes.length / 2);
            byte[] part1 = java.util.Arrays.copyOfRange(respBytes, 0, half);
            byte[] part2 = java.util.Arrays.copyOfRange(respBytes, half, respBytes.length);
            out.write(0x02); out.write(part1.length); out.write(part1); out.flush(); // FIN=0 binary
            Thread.sleep(5);
            out.write(0x80); out.write(part2.length); out.write(part2); out.flush(); // FIN=1 continuation
        }

        @Override public void close() throws IOException { server.close(); }
    }

    @Test @Timeout(10)
    void connect_handlesFragmentedResponse_accumulatesBeforeParsing() throws Exception {
        // The ping response arrives in two WebSocket frames (FIN=0, then FIN=1).
        // If the frame reader parsed after the first fragment, it would get a truncated proto
        // and parseFrom() would throw. A successful connect() proves accumulation works.
        try (FragmentingPingServer frag = new FragmentingPingServer()) {
            frag.acceptAsync();
            QuarkusSC2Transport t = new QuarkusSC2Transport();
            t.sc2Port = frag.port();
            t.skipProcessLaunch = true;
            t.connectRetryCount = 3;
            t.connectRetryIntervalMs = 100;
            try {
                t.connect(); // throws if accumulation broken (truncated proto → parse error)
            } finally {
                t.shutdown();
            }
        }
    }

    @Test @Timeout(10)
    void connect_handlesPartialFrames_onPing() throws Exception {
        transport = connectedTransport(server);
        assertThat(transport.isRunning()).isFalse();
        assertThat(server.received().stream().anyMatch(Sc2Api.Request::hasPing)).isTrue();
    }

    // ---------------------------------------------------------------------------
    // extractLocalPlayerId() — player ID from ResponseGameInfo
    // ---------------------------------------------------------------------------

    @Test
    void extractLocalPlayerId_returnsParticipantId_whenParticipantAndComputerPresent() {
        // PARTICIPANT with id=2, COMPUTER with id=1 — must return 2
        SC2APIProtocol.Sc2Api.ResponseGameInfo proto = SC2APIProtocol.Sc2Api.ResponseGameInfo.newBuilder()
            .setMapName("test")
            .addPlayerInfo(SC2APIProtocol.Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(1)
                .setType(SC2APIProtocol.Sc2Api.PlayerType.Computer)
                .setRaceRequested(SC2APIProtocol.Common.Race.Terran)
                .build())
            .addPlayerInfo(SC2APIProtocol.Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(2)
                .setType(SC2APIProtocol.Sc2Api.PlayerType.Participant)
                .setRaceRequested(SC2APIProtocol.Common.Race.Protoss)
                .build())
            .setOptions(SC2APIProtocol.Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
            .build();
        Sc2Api.Response resp = Sc2Api.Response.newBuilder()
            .setGameInfo(proto).setStatus(Sc2Api.Status.in_game).build();
        com.github.ocraft.s2client.protocol.response.ResponseGameInfo gameInfo =
            com.github.ocraft.s2client.protocol.response.ResponseGameInfo.from(resp);

        assertThat(QuarkusSC2Transport.extractLocalPlayerId(gameInfo)).isEqualTo(2);
    }

    @Test
    void extractLocalPlayerId_returnsFallback_whenNoParticipantPresent() {
        // Only COMPUTER entries — no PARTICIPANT → fallback to 1
        SC2APIProtocol.Sc2Api.ResponseGameInfo proto = SC2APIProtocol.Sc2Api.ResponseGameInfo.newBuilder()
            .setMapName("test")
            .addPlayerInfo(SC2APIProtocol.Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(3)
                .setType(SC2APIProtocol.Sc2Api.PlayerType.Computer)
                .setRaceRequested(SC2APIProtocol.Common.Race.Zerg)
                .build())
            .setOptions(SC2APIProtocol.Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
            .build();
        Sc2Api.Response resp = Sc2Api.Response.newBuilder()
            .setGameInfo(proto).setStatus(Sc2Api.Status.in_game).build();
        com.github.ocraft.s2client.protocol.response.ResponseGameInfo gameInfo =
            com.github.ocraft.s2client.protocol.response.ResponseGameInfo.from(resp);

        assertThat(QuarkusSC2Transport.extractLocalPlayerId(gameInfo)).isEqualTo(1);
    }

    @Test
    void extractLocalPlayerId_returnsSingleParticipantId() {
        // Single PARTICIPANT — standard case (bot vs AI, bot is player 1)
        SC2APIProtocol.Sc2Api.ResponseGameInfo proto = SC2APIProtocol.Sc2Api.ResponseGameInfo.newBuilder()
            .setMapName("test")
            .addPlayerInfo(SC2APIProtocol.Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(1)
                .setType(SC2APIProtocol.Sc2Api.PlayerType.Participant)
                .setRaceRequested(SC2APIProtocol.Common.Race.Protoss)
                .build())
            .setOptions(SC2APIProtocol.Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
            .build();
        Sc2Api.Response resp = Sc2Api.Response.newBuilder()
            .setGameInfo(proto).setStatus(Sc2Api.Status.in_game).build();
        com.github.ocraft.s2client.protocol.response.ResponseGameInfo gameInfo =
            com.github.ocraft.s2client.protocol.response.ResponseGameInfo.from(resp);

        assertThat(QuarkusSC2Transport.extractLocalPlayerId(gameInfo)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------
    // naturalGameEnd — onGameEnd(GameResult) receives correct result
    // ---------------------------------------------------------------------------

    @Test @Timeout(10)
    void naturalGameEnd_callsOnGameEnd_withWin_whenPlayerResultIsVictory() throws Exception {
        server.observationsBeforeEnd = 1;
        server.playerResultForGameEnd = Sc2Api.Result.Victory;
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        java.util.concurrent.atomic.AtomicReference<io.quarkmind.sc2.GameResult> capturedResult =
            new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(com.github.ocraft.s2client.protocol.response.ResponseGameInfo i) {}
            @Override public void onStep(com.github.ocraft.s2client.protocol.observation.Observation obs) throws InterruptedException {}
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) {
                capturedResult.set(result);
                gameEnded.countDown();
            }
        });

        assertThat(gameEnded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(capturedResult.get()).isEqualTo(io.quarkmind.sc2.GameResult.WIN);
    }

    @Test @Timeout(10)
    void naturalGameEnd_callsOnGameEnd_withLoss_whenPlayerResultIsDefeat() throws Exception {
        server.observationsBeforeEnd = 1;
        server.playerResultForGameEnd = Sc2Api.Result.Defeat;
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        java.util.concurrent.atomic.AtomicReference<io.quarkmind.sc2.GameResult> capturedResult =
            new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(com.github.ocraft.s2client.protocol.response.ResponseGameInfo i) {}
            @Override public void onStep(com.github.ocraft.s2client.protocol.observation.Observation obs) throws InterruptedException {}
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) {
                capturedResult.set(result);
                gameEnded.countDown();
            }
        });

        assertThat(gameEnded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(capturedResult.get()).isEqualTo(io.quarkmind.sc2.GameResult.LOSS);
    }

    @Test @Timeout(10)
    void naturalGameEnd_callsOnGameEnd_withUnknown_whenNoPlayerResult() throws Exception {
        server.observationsBeforeEnd = 1;
        server.playerResultForGameEnd = null; // no playerResult in response
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        java.util.concurrent.atomic.AtomicReference<io.quarkmind.sc2.GameResult> capturedResult =
            new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(com.github.ocraft.s2client.protocol.response.ResponseGameInfo i) {}
            @Override public void onStep(com.github.ocraft.s2client.protocol.observation.Observation obs) throws InterruptedException {}
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) {
                capturedResult.set(result);
                gameEnded.countDown();
            }
        });

        assertThat(gameEnded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(capturedResult.get()).isEqualTo(io.quarkmind.sc2.GameResult.UNKNOWN);
    }

    @Test @Timeout(10)
    void quit_callsOnGameEnd_withUnknown_whenGameInterrupted() throws Exception {
        server.observationsBeforeEnd = Integer.MAX_VALUE;
        transport = connectedTransport(server);
        transport.createGame();
        transport.joinGame();

        java.util.concurrent.atomic.AtomicReference<io.quarkmind.sc2.GameResult> capturedResult =
            new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch stepped   = new CountDownLatch(1);
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(com.github.ocraft.s2client.protocol.response.ResponseGameInfo i) {}
            @Override public void onStep(com.github.ocraft.s2client.protocol.observation.Observation obs) throws InterruptedException {
                stepped.countDown();
            }
            @Override public void onGameEnd(io.quarkmind.sc2.GameResult result) {
                capturedResult.set(result);
                gameEnded.countDown();
            }
        });

        assertThat(stepped.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        transport.quit();
        assertThat(gameEnded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(capturedResult.get()).isEqualTo(io.quarkmind.sc2.GameResult.UNKNOWN);
    }
}
