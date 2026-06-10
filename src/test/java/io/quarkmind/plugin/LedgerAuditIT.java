package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.StrategySelector;
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

    // Concrete injection: L6 introduced competing StrategyTask implementations; DroolsStrategyTask
    // is the specific subject of this ledger audit test.
    @Inject @CaseType("starcraft-game") DroolsStrategyTask strategyTask;
    @Inject StrategySelector strategySelector;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject GameSession gameSession;
    @Inject IntentQueue intentQueue;

    @BeforeEach
    void setup() {
        gameSession.reset();
        intentQueue.drainAll();
        strategySelector.selectForGame("strategy.drools", QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
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
        CaseFile cf = caseFile(200, 0, workers(4), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(cf);
        Thread.sleep(500);
        List<LedgerEntry> entries = ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(entries)
                .as("Expected at least one ledger entry from DroolsStrategyTask")
                .isNotEmpty();
        assertThat(entries)
                .anyMatch(e -> "strategy.drools".equals(e.actorId));
    }

    // --- Helpers matching DroolsStrategyTaskTest pattern ---

    private CaseFile caseFile(int minerals, int vespene, List<Unit> workers,
                               List<Building> buildings,
                               String enemyPosture, boolean timingAttack) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.MINERALS,               minerals);
        cf.put(QuarkMindCaseFile.VESPENE,                vespene);
        cf.put(QuarkMindCaseFile.WORKERS,                workers);
        cf.put(QuarkMindCaseFile.ARMY,                   List.of());
        cf.put(QuarkMindCaseFile.MY_BUILDINGS,           buildings);
        cf.put(QuarkMindCaseFile.GEYSERS,                List.of());
        cf.put(QuarkMindCaseFile.RESOURCE_BUDGET,        new ResourceBudget(minerals, vespene));
        cf.put(QuarkMindCaseFile.READY,                  Boolean.TRUE);
        cf.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE,        0);
        cf.put(QuarkMindCaseFile.ENEMY_POSTURE,          enemyPosture);
        cf.put(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, timingAttack);
        return cf;
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
