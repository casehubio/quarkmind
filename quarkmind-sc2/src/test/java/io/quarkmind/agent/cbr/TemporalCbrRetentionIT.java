package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TemporalCbrRetentionIT {

    @Inject TimelineSampler timelineSampler;
    @Inject SC2CbrRetentionObserver retentionObserver;

    @Test
    void fullGameLifecycle_storesTimelineFeature() {
        timelineSampler.onGameStarted(new io.quarkmind.sc2.GameStarted());

        for (int i = 0; i < 5; i++) {
            long frame = i * 672L;
            int workers = 12 + i;
            int minerals = 50 + i * 50;
            int supply = workers + i * 2;
            var gs = new GameState(minerals, 0, supply, supply,
                    buildWorkers(workers),
                    List.of(new Building("b1", BuildingType.NEXUS, new Point2d(20, 20), 1000, 1000, true)),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
            timelineSampler.tick(gs);
        }

        assertThat(timelineSampler.getTimeline()).hasSize(5);

        var gameState = new GameState(250, 0, 24, 24,
                buildWorkers(16),
                List.of(new Building("b1", BuildingType.NEXUS, new Point2d(20, 20), 1000, 1000, true)),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                2688L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());

        CaseOutcomeEvent event = new CaseOutcomeEvent(
                "starcraft-game", "test-tenant", UUID.randomUUID(),
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.85,
                        QuarkMindCaseFile.GAME_STATE, gameState,
                        QuarkMindCaseFile.OPPONENT_ID, "test-opponent"),
                "WIN", Instant.now(), Map.of());

        retentionObserver.onOutcome(event);
    }

    private static List<Unit> buildWorkers(int count) {
        var workers = new java.util.ArrayList<Unit>();
        for (int i = 0; i < count; i++) {
            workers.add(new Unit("w" + i, UnitType.PROBE,
                    new Point2d(10 + i, 10), 20, 20, 20, 20, 0, 0));
        }
        return workers;
    }
}
