package io.quarkmind.plugin;

import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.cbr.SC2StrategyRouterTask;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class LedgerAuditIT {

    @Inject DroolsStrategyTask strategyTask;
    @Inject SC2StrategyRouterTask strategyRouter;
    @Inject InMemoryLedgerEntryRepository ledgerRepo;
    @Inject GameSession gameSession;
    @Inject IntentQueue intentQueue;

    @BeforeEach
    void setup() {
        gameSession.reset();
        intentQueue.drainAll();
        // Router fallback is strategy.drools when broker has no archetype
        strategyTask.resetPrevStrategy(); // prevents prevStrategy leakage from earlier @QuarkusTest runs
    }

    @AfterEach
    void cleanup() {
        intentQueue.drainAll();
    }

    /**
     * SINGLE TEST METHOD BY DESIGN: DroolsStrategyTask.prevStrategy persists across @Test methods
     * on the same @ApplicationScoped CDI bean. The first call (prevStrategy=null) always fires a
     * transition. A second method using the same CaseFile state will NOT fire — event never comes,
     * assertion fails. Future tests must produce a different strategy output, or add a test-only
     * clearPrevState() method to DroolsStrategyTask.
     */
    @Test
    void strategyTransitionWritesLedgerEntry() throws InterruptedException {
        var ctx = caseContext(200, 0, workers(4), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        Thread.sleep(500);
        List<LedgerEntry> entries = ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(entries)
                .as("Expected at least one ledger entry from DroolsStrategyTask")
                .isNotEmpty();
        assertThat(entries)
                .anyMatch(e -> "strategy.drools".equals(e.actorId));
    }

    // --- Helpers matching DroolsStrategyTaskTest pattern ---

    private MutableMapCaseContext caseContext(int minerals, int vespene, List<Unit> workers,
                               List<Building> buildings,
                               String enemyPosture, boolean timingAttack) {
        return new MutableMapCaseContext(Map.ofEntries(
            Map.entry(QuarkMindCaseFile.MINERALS,               minerals),
            Map.entry(QuarkMindCaseFile.VESPENE,                vespene),
            Map.entry(QuarkMindCaseFile.WORKERS,                workers),
            Map.entry(QuarkMindCaseFile.ARMY,                   List.of()),
            Map.entry(QuarkMindCaseFile.MY_BUILDINGS,           buildings),
            Map.entry(QuarkMindCaseFile.GEYSERS,                List.of()),
            Map.entry(QuarkMindCaseFile.RESOURCE_BUDGET,        new ResourceBudget(minerals, vespene)),
            Map.entry(QuarkMindCaseFile.READY,                  Boolean.TRUE),
            Map.entry(QuarkMindCaseFile.ENEMY_ARMY_SIZE,        0),
            Map.entry(QuarkMindCaseFile.ENEMY_POSTURE,          enemyPosture),
            Map.entry(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, timingAttack)
        ));
    }

    private List<Unit> workers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Unit("p-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0, 0))
                .toList();
    }

    private Building nexus() {
        return bldg("n-0", BuildingType.NEXUS, true);
    }

    private Building bldg(String tag, BuildingType type, boolean complete) {
        return new Building(tag, type, new Point2d(10, 10), 500, 500, complete);
    }
}
