package io.quarkmind.sc2.mock;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.ScenarioRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AdaptivePluginSelectionIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame     simulatedGame;
    @Inject IntentQueue       intentQueue;
    @Inject ScenarioRunner    scenarioRunner;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
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
}
