package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.LocalMap;
import com.github.ocraft.s2client.protocol.game.Race;
import java.nio.file.Files;
import java.nio.file.Path;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.SC2Engine;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real SC2 engine — connects to a live StarCraft II process via ocraft-s2client.
 * Active only in the {@code %sc2} profile.
 *
 * <p>ocraft is callback-driven: {@link SC2BotAgent#onStep()} fires each game frame
 * and is the only place where SC2 commands may be issued. Consequently:
 * <ul>
 *   <li>{@link #tick()} is a no-op — ocraft owns the game clock.</li>
 *   <li>{@link #dispatch()} is a no-op — {@code SC2BotAgent.onStep()} drains {@link IntentQueue}.</li>
 *   <li>{@link #observe()} polls the {@link GameState} stored by the most recent {@code onStep()}.</li>
 * </ul>
 *
 * <p>{@link #getBotAgent()} exposes the agent reference for {@link SC2DebugScenarioRunner}.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class RealSC2Engine implements SC2Engine {

    private static final Logger log = Logger.getLogger(RealSC2Engine.class);

    @Inject IntentQueue intentQueue;
    @Inject SC2BotAgent botAgent;

    @ConfigProperty(name = "starcraft.sc2.map", defaultValue = "TorchesAIE_v4")
    String mapName;

    @ConfigProperty(name = "starcraft.sc2.difficulty", defaultValue = "VERY_EASY")
    String difficultyStr;

    @ConfigProperty(name = "starcraft.sc2.port", defaultValue = "8168")
    int sc2Port;

    private S2Coordinator coordinator;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private CompletableFuture<Void> gameLoop;

    // --- Lifecycle ---

    @Override
    @Fallback(fallbackMethod = "connectFallback")
    public void connect() {
        log.info("[SC2] Connecting to StarCraft II...");
        try {
            Difficulty difficulty = Difficulty.valueOf(difficultyStr);
            SC2Executable sc2 = resolveSC2Executable();
            log.infof("[SC2] Using executable: %s (build %s)", sc2.path(), sc2.baseBuild());
            // ocraft bug: getBaseBuildFromGameExePath() uses "\\" (Windows separator), returns null on Mac.
            // Setting the system property makes Typesafe Config supply customBaseBuild,
            // skipping both the broken path-parse and the null.replaceFirst() NPE that follows.
            // Also extend the SC2 startup wait: default retry=10 × 2s = 20s is too short on Mac.
            System.setProperty("ocraft.game.executable.baseBuild", sc2.baseBuild());
            System.setProperty("ocraft.game.net.retry", "60");       // 60 × 5s = 300s max wait
            System.setProperty("ocraft.game.net.timeoutInMillis", "5000"); // diagnostic: 5s each
            coordinator = S2Coordinator.setup()
                    .setProcessPath(sc2.path())
                    .setPortStart(sc2Port)
                    .setParticipants(
                            S2Coordinator.createParticipant(Race.PROTOSS, botAgent),
                            S2Coordinator.createComputer(Race.RANDOM, difficulty)
                    )
                    .launchStarcraft();
            connected.set(true);
            log.info("[SC2] Connected — coordinator ready");
        } catch (Throwable e) {
            log.errorf("[SC2] Connection attempt failed: %s", e.getMessage());
            throw new RuntimeException("[SC2] wrapped connect failure", e);
        }
    }

    // ocraft 0.4.21 bug: ExecutableParser.getBaseBuildFromGameExePath() splits on "\\" (Windows
    // separator) so returns null on Mac. That null later causes NPE in Versions.versionFor().
    // We read ExecuteInfo.txt ourselves and pass the resolved path + build directly to ocraft,
    // using setDataVersion() to supply a non-null string that skips the broken code path.
    private static SC2Executable resolveSC2Executable() {
        Path executeInfo = Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "Blizzard", "StarCraft II", "ExecuteInfo.txt");
        try {
            Path exePath = Files.lines(executeInfo)
                    .findFirst()
                    .map(line -> line.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .map(parts -> parts[1].trim())
                    .map(Path::of)
                    .filter(Files::exists)
                    .orElseThrow(() -> new RuntimeException("SC2 executable not found in " + executeInfo));
            String baseBuild = "BaseUnknown";
            for (int i = 0; i < exePath.getNameCount(); i++) {
                String segment = exePath.getName(i).toString();
                if (segment.startsWith("Base")) { baseBuild = segment; break; }
            }
            return new SC2Executable(exePath, baseBuild);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read SC2 executable path from " + executeInfo, e);
        }
    }

    private record SC2Executable(Path path, String baseBuild) {}

    public void connectFallback() {
        log.error("[SC2] Failed to connect after retries — bot will run without SC2");
    }

    @Override
    public void joinGame() {
        if (coordinator == null) {
            log.error("[SC2] Cannot join game — coordinator not initialised");
            return;
        }
        Path mapFile = Path.of(System.getProperty("user.home"), ".quarkmind", "maps", mapName + ".SC2Map");
        log.infof("[SC2] Starting game on map: %s (%s)", mapName, mapFile);
        coordinator.startGame(LocalMap.of(mapFile));

        // Run ocraft's game loop in a background thread.
        // coordinator.update() returns false when the game ends.
        gameLoop = CompletableFuture.runAsync(() -> {
            log.info("[SC2] Game loop started");
            while (coordinator.update()) {
                // SC2BotAgent.onStep() is called by ocraft each game frame
            }
            connected.set(false);
            log.info("[SC2] Game loop ended");
        }).exceptionally(e -> {
            log.errorf("[SC2] Game loop error: %s", e.getMessage());
            connected.set(false);
            return null;
        });
    }

    @Override
    public void leaveGame() {
        connected.set(false);
        if (coordinator != null) coordinator.quit();
        if (gameLoop != null) gameLoop.cancel(true);
        log.info("[SC2] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    // --- Per-tick: no-ops for real SC2 ---

    /** No-op — ocraft drives the game clock via {@code coordinator.update()}. */
    @Override
    public void tick() {}

    /** Polls the GameState stored by the most recent {@code SC2BotAgent.onStep()} call. */
    @Override
    public GameState observe() {
        GameState state = botAgent.getLatestGameState();
        if (state == null) {
            log.debug("[SC2] No observation yet — first frame pending");
            return emptyState();
        }
        return state;
    }

    /** No-op — {@code SC2BotAgent.onStep()} drains {@link IntentQueue} within the SC2 frame callback. */
    @Override
    public void dispatch() {}

    // --- Extension point for SC2DebugScenarioRunner ---

    /** Returns the underlying bot agent. Used by {@link SC2DebugScenarioRunner} to enqueue debug commands. */
    public SC2BotAgent getBotAgent() {
        return botAgent;
    }

    // --- Helpers ---

    private static GameState emptyState() {
        return new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
    }
}
