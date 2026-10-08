package io.quarkmind.sc2.real;

import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStopped;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays multiple SC2 games in sequence. Observes {@link GameStopped} and
 * starts the next game until the configured count is reached.
 *
 * <p>Active only in the {@code %sc2} profile. When {@code game-count} is 1
 * (the default), this bean acts as a no-op — the single-game lifecycle is
 * unchanged.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class MultiGameController {

    private static final Logger log = Logger.getLogger(MultiGameController.class);

    @Inject AgentOrchestrator orchestrator;

    @ConfigProperty(name = "starcraft.sc2.game-count", defaultValue = "1")
    int gameCount;

    @ConfigProperty(name = "starcraft.sc2.delay-between-games-ms", defaultValue = "5000")
    long delayBetweenGamesMs;

    private final AtomicInteger completed = new AtomicInteger(0);
    private final List<GameResult> gameResults = Collections.synchronizedList(new ArrayList<>());

    void onGameStopped(@Observes GameStopped event) {
        int done = completed.incrementAndGet();
        gameResults.add(event.result());
        log.infof("[MultiGame] Game %d/%d completed — result: %s", done, gameCount, event.result());

        if (done < gameCount) {
            Thread.ofVirtual().name("sc2-multi-game-" + (done + 1)).start(() -> {
                try {
                    Thread.sleep(delayBetweenGamesMs);
                    log.infof("[MultiGame] Starting game %d/%d...", done + 1, gameCount);
                    orchestrator.startGame();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[MultiGame] Interrupted — stopping multi-game sequence");
                } catch (Exception e) {
                    log.errorf("[MultiGame] Failed to start game %d: %s", done + 1, e.getMessage());
                }
            });
        } else {
            log.infof("[MultiGame] All %d games completed — %s", gameCount, summary());
            log.infof("[MultiGame] Replays saved by SC2 to: %s",
                Path.of(System.getProperty("user.home"),
                    "Library", "Application Support", "Blizzard",
                    "StarCraft II", "Accounts").toAbsolutePath());
        }
    }

    int gamesCompleted() {
        return completed.get();
    }

    List<GameResult> results() {
        return List.copyOf(gameResults);
    }

    boolean isAllGamesComplete() {
        return completed.get() >= gameCount;
    }

    String summary() {
        long wins = gameResults.stream().filter(r -> r == GameResult.WIN).count();
        long losses = gameResults.stream().filter(r -> r == GameResult.LOSS).count();
        long other = gameResults.size() - wins - losses;
        StringBuilder sb = new StringBuilder();
        sb.append(gameResults.size()).append(" games: ");
        sb.append(wins).append("W ").append(losses).append("L");
        if (other > 0) sb.append(" ").append(other).append(" other");
        return sb.toString();
    }
}
