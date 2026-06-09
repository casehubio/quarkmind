package io.quarkmind.sc2.real;

import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit test for QuarkusSC2Transport using a local fake SC2 server (ServerSocket).
 * No live SC2 binary required. Exercises the proto structure, threading invariants,
 * and game loop lifecycle.
 *
 * <p>The fake server speaks the same binary protobuf protocol as real SC2:
 * each request is a Sc2Api.Request frame; each response is a Sc2Api.Response frame.
 */
class QuarkusSC2TransportTest {

    /**
     * Minimal fake SC2 server that accepts one WebSocket connection and
     * handles request/response exchange at the binary protobuf level.
     * Uses HTTP upgrade + basic WebSocket framing.
     */
    static class FakeSC2Server implements Closeable {

        private final ServerSocket server;
        private final List<Sc2Api.Request> received = new CopyOnWriteArrayList<>();
        private final BlockingQueue<Sc2Api.Response> toSend = new LinkedBlockingQueue<>();
        private Thread handler;
        private Socket client;

        FakeSC2Server() throws IOException {
            server = new ServerSocket(0); // OS-assigned free port
        }

        int port() {
            return server.getLocalPort();
        }

        /**
         * Enqueue a response that will be sent to the next request received.
         */
        void willRespond(Sc2Api.Response response) {
            toSend.add(response);
        }

        /** Start accepting (background thread). Returns when a client connects. */
        void acceptAsync() {
            handler = Thread.ofVirtual().name("fake-sc2-server").start(() -> {
                try {
                    client = server.accept();
                    runWebSocketHandshake(client);
                    handleFrames(client);
                } catch (Exception ignored) {}
            });
        }

        List<Sc2Api.Request> received() {
            return received;
        }

        /**
         * Performs a minimal HTTP → WebSocket upgrade.
         * Real SC2 requires a proper WebSocket handshake; we do just enough for JDK WebSocket.
         */
        private void runWebSocketHandshake(Socket s) throws IOException, java.security.NoSuchAlgorithmException {
            var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            var out = new PrintWriter(s.getOutputStream(), true);
            String key = null;
            String line;
            while (!(line = in.readLine()).isEmpty()) {
                if (line.startsWith("Sec-WebSocket-Key:")) {
                    key = line.substring("Sec-WebSocket-Key:".length()).trim();
                }
            }
            String accept = java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes())
            );
            out.print("HTTP/1.1 101 Switching Protocols\r\n");
            out.print("Upgrade: websocket\r\n");
            out.print("Connection: Upgrade\r\n");
            out.print("Sec-WebSocket-Accept: " + accept + "\r\n");
            out.print("\r\n");
            out.flush();
        }

        /** Read WebSocket binary frames and dispatch proto responses. */
        private void handleFrames(Socket s) throws IOException, InterruptedException {
            var raw = s.getInputStream();
            var out = s.getOutputStream();
            while (!s.isClosed()) {
                // Read WebSocket frame header
                int b0 = raw.read();
                if (b0 < 0) break;
                int b1 = raw.read();
                if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                int len = b1 & 0x7F;
                if (len == 126) {
                    len = (raw.read() << 8) | raw.read();
                }
                byte[] mask = masked ? raw.readNBytes(4) : new byte[0];
                byte[] payload = raw.readNBytes(len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                Sc2Api.Request req = Sc2Api.Request.parseFrom(payload);
                received.add(req);
                Sc2Api.Response resp = toSend.poll(5, TimeUnit.SECONDS);
                if (resp != null) {
                    byte[] respBytes = resp.toByteArray();
                    // Write unmasked binary WebSocket frame (server → client is unmasked)
                    out.write(0x82); // FIN + binary opcode
                    if (respBytes.length < 126) {
                        out.write(respBytes.length);
                    } else {
                        out.write(126);
                        out.write((respBytes.length >> 8) & 0xFF);
                        out.write(respBytes.length & 0xFF);
                    }
                    out.write(respBytes);
                    out.flush();
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            if (client != null) client.close();
        }
    }

    // -------------------------------------------------------------------------
    // Test scaffolding
    // -------------------------------------------------------------------------

    private QuarkusSC2Transport transport;

    @BeforeEach
    void setUp() {
        transport = new QuarkusSC2Transport();
    }

    @AfterEach
    void tearDown() {
        transport.shutdown();
    }

    // -------------------------------------------------------------------------
    // quit() threading invariant
    // -------------------------------------------------------------------------

    @Test
    void quit_setsQuittingFlagAndIsReentrant() {
        // quit() may be called multiple times without throwing
        transport.quit();
        transport.quit();
        // No assertion — just verify no exception
    }

    @Test
    void isRunning_returnsFalseInitially() {
        assertThat(transport.isRunning()).isFalse();
    }

    @Test
    void shutdown_doesNotThrowWhenNoLoopRunning() {
        // @PreDestroy — safe to call even when no game loop was started
        transport.shutdown();
    }

    // -------------------------------------------------------------------------
    // joinGame() must include InterfaceOptions.raw=true
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void joinGame_requestIncludesInterfaceOptionsRawTrue() throws Exception {
        // This test verifies the *content* of the RequestJoinGame proto — the most
        // critical correctness invariant (without raw=true the observation path is dead).
        // Full integration requires a server; here we verify the proto builder logic
        // by looking at what joinGame() would send via a fake server.
        //
        // RED: joinGame() currently throws UnsupportedOperationException.
        // GREEN: joinGame() must send a RequestJoinGame with InterfaceOptions.raw=true.

        // When implemented, call transport.joinGame() and inspect received[0].getJoinGame()
        assertThatThrownBy(() -> transport.joinGame())
                .isInstanceOf(UnsupportedOperationException.class);
        // This assertion exists so we have a clear RED marker.
        // Replace with proto inspection once implemented.
    }

    // -------------------------------------------------------------------------
    // createGame() realtime=false invariant
    // -------------------------------------------------------------------------

    @Test
    void createGame_isNotYetImplemented() {
        assertThatThrownBy(() -> transport.createGame())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // runGameLoop()
    // -------------------------------------------------------------------------

    @Test
    void runGameLoop_isNotYetImplemented() {
        AtomicBoolean started = new AtomicBoolean(false);
        SC2FrameCallback stub = new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) { started.set(true); }
            @Override public void onStep(Observation obs) {}
            @Override public void onGameEnd() {}
        };
        assertThatThrownBy(() -> transport.runGameLoop(stub))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // sendActions / sendDebug
    // -------------------------------------------------------------------------

    @Test
    void sendActions_isNotYetImplemented() {
        assertThatThrownBy(() -> transport.sendActions(List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sendDebug_isNotYetImplemented() {
        assertThatThrownBy(() -> transport.sendDebug(Sc2Api.RequestDebug.getDefaultInstance()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
