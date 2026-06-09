package io.quarkmind.sc2.real;

import SC2APIProtocol.Sc2Api;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quarkus-native SC2 WebSocket transport — replaces ocraft's S2Coordinator + Vert.x verticle.
 *
 * <p>Uses {@code java.net.http.WebSocket} (JDK built-in) for a single WebSocket connection
 * to SC2's remote API at {@code ws://127.0.0.1:{port}/sc2api}. Exchanges binary protobuf
 * frames in a strict request/response sequence. The game loop runs on a virtual thread
 * (Java 21). All I/O calls ({@link #sendActions}, {@link #sendDebug}) are safe only from
 * the game loop virtual thread.
 *
 * <p>Thread safety invariant: {@code sendSync()} may only be called from the game loop
 * virtual thread. {@code quit()} is safe from any thread — it sets a flag and interrupts.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class QuarkusSC2Transport {

    // ---------------------------------------------------------------------------
    // Configuration — all SC2 game-setup properties owned by the transport
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

    /** Stored so {@link #quit()} can interrupt it. Written by runGameLoop(); nulled in finally. */
    private volatile Thread gameLoopThread;

    /** True while game loop is alive; set to false first in finally block. */
    private volatile boolean running = false;

    /** Set by quit() before interrupting the game loop thread. */
    private final AtomicBoolean quitting = new AtomicBoolean(false);

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /** Resolve SC2 exe, launch process, TCP probe, WS connect + ping. */
    public void connect() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** RequestCreateGame (reads map, difficulty, aiRace from config). realtime NOT set. */
    public void createGame() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** RequestJoinGame with InterfaceOptions.raw=true (reads botRace from config). */
    public void joinGame() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Stores thread ref; starts virtual thread: preamble (requestGameInfo + onGameStart)
     * then observation loop with try/catch/finally that sets running=false + calls onGameEnd.
     */
    public void runGameLoop(SC2FrameCallback callback) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Sets quitting=true + interrupts game loop thread (safe from any thread).
     * RequestQuit + WS close happen inside the game loop's finally block.
     */
    public void quit() {
        quitting.set(true);
        Thread t = gameLoopThread;
        if (t != null) t.interrupt();
    }

    /** Delegates to {@code running} — used by RealSC2Engine.isConnected(). */
    public boolean isRunning() {
        return running;
    }

    /** Calls quit(); game loop thread handles RequestQuit + WS close + onGameEnd(). */
    @PreDestroy
    public void shutdown() {
        quit();
    }

    // ---------------------------------------------------------------------------
    // Called from game loop virtual thread only
    // ---------------------------------------------------------------------------

    /** Build RequestAction proto from commands and send. Only safe from game loop thread. */
    public void sendActions(List<ResolvedCommand> commands) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** Send a RequestDebug proto. Only safe from game loop thread. */
    public void sendDebug(Sc2Api.RequestDebug req) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
