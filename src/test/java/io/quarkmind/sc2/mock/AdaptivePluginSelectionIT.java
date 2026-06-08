package io.quarkmind.sc2.mock;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.ScenarioRunner;
import io.quarkmind.domain.Point2d;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BlinkIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AdaptivePluginSelectionIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame     simulatedGame;
    @Inject IntentQueue       intentQueue;
    @Inject ScenarioRunner    scenarioRunner;
    @Inject @CaseType("starcraft-game") TacticsTask tacticsTask;
    @Inject @CaseType("starcraft-game") StrategyTask strategyTask;
    @Inject ScoutingIntelBroker broker;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();   // fires GameStarted → broker.onGameStarted() clears latest
        intentQueue.drainAll();
        broker.clearLatest();       // defensive reset — independent of event chain
    }

    @AfterEach
    void tearDown() {
        intentQueue.drainAll();
    }

    @Test
    void tickResultReturnsValidCaseFile() {
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

        assertThat(result).isNotNull();
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseFile().contains(QuarkMindCaseFile.READY)).isTrue();
    }

    @Test
    void tacticsSkippedWhenNoEnemiesVisible() {
        // Default reset state: no enemies → ENEMY_UNITS is empty in the translator output,
        // and NEAREST_THREAT is never written (scouting only writes it when enemies are present).
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

        assertThat(result.solveSucceeded()).isTrue();
        // Translator wrote ENEMY_UNITS but it is empty — no enemies observed
        assertThat(result.caseFile().contains(QuarkMindCaseFile.ENEMY_UNITS)).isTrue();
        // NEAREST_THREAT removed (#179) — broker THREAT_POSITION is empty when no enemies
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
        // canActivate returns false because broker has no threat position
        assertThat(tacticsTask.canActivate(result.caseFile())).isFalse();
        // No tactical intents dispatched
        assertThat(intentQueue.drainAll())
            .noneMatch(i -> i instanceof AttackIntent || i instanceof BlinkIntent);
    }

    @Test
    void strategyRequiresScoutingOutputToActivate() {
        // The tick CaseFile (initial state from translator) contains READY but not ENEMY_ARMY_SIZE.
        // Strategy requires ENEMY_ARMY_SIZE (written by scouting) to activate — proving
        // that scouting must run before strategy in the ordered chain.
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

        assertThat(result.solveSucceeded()).isTrue();
        // Translator wrote READY — that's the only prerequisite scouting needs
        assertThat(result.caseFile().contains(QuarkMindCaseFile.READY)).isTrue();
        // ENEMY_ARMY_SIZE absent in initial tick state — strategy is blocked until scouting runs
        assertThat(result.caseFile().contains(QuarkMindCaseFile.ENEMY_ARMY_SIZE)).isFalse();
        // Confirm: strategy cannot activate without ENEMY_ARMY_SIZE
        assertThat(strategyTask.canActivate(result.caseFile())).isFalse();

        // Positive case: strategy activates once scouting has written ENEMY_ARMY_SIZE
        // synthetic CaseFile — created out-of-band from CDI to test canActivate() in isolation
        var withScouting = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        withScouting.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        withScouting.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN")); // satisfy broker gate
        assertThat(strategyTask.canActivate(withScouting)).isTrue();
    }

    @Test
    void tacticsActivatesWhenNearestThreatAndStrategyPresent() {
        // After spawn-enemy-attack, ENEMY_UNITS is populated by the translator.
        // Scouting (running asynchronously) writes NEAREST_THREAT from ENEMY_UNITS.
        // Prove that tactics canActivate only when NEAREST_THREAT is present.
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

        assertThat(result.solveSucceeded()).isTrue();
        // Translator observed enemies — ENEMY_UNITS is populated
        assertThat(result.caseFile().contains(QuarkMindCaseFile.ENEMY_UNITS)).isTrue();

        // Prove tactics gate: canActivate is false when broker has no threat position
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
        assertThat(tacticsTask.canActivate(result.caseFile())).isFalse();

        // Simulate scouting + strategy output: tactics gate is met when both gates satisfied
        broker.update(new ScoutingIntelPayload.ThreatPosition(new Point2d(50, 50)));
        result.caseFile().put(QuarkMindCaseFile.STRATEGY, "DEFEND");
        assertThat(tacticsTask.canActivate(result.caseFile())).isTrue();
    }
}
