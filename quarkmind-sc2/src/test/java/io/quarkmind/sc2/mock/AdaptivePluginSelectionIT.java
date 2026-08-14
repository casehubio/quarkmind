package io.quarkmind.sc2.mock;

import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.agent.PluginDispatchBroker;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.cbr.SC2StrategyRouterTask;
import io.quarkmind.plugin.DroolsStrategyTask;
import io.quarkmind.plugin.DroolsTacticsTask;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.ScenarioRunner;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BlinkIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AdaptivePluginSelectionIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame     simulatedGame;
    @Inject IntentQueue       intentQueue;
    @Inject ScenarioRunner    scenarioRunner;
    @Inject DroolsTacticsTask tacticsTask;
    @Inject DroolsStrategyTask strategyTask;
    @Inject SC2StrategyRouterTask strategyRouter;
    @Inject ScoutingIntelBroker broker;
    @Inject PluginDispatchBroker dispatchBroker;
    @Inject MessageService        messageService;
    private long afterId;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
        intentQueue.drainAll();
        broker.clearLatest();
        afterId = dispatchBroker.lastDispatchedId();
    }

    @AfterEach
    void tearDown() {
        intentQueue.drainAll();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        broker.clearLatest();
    }

    @Test
    void tickResultReturnsValidCaseContext() {
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result).isNotNull();
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseContext().contains(QuarkMindCaseFile.READY)).isTrue();
    }

    @Test
    void tacticsSkippedWhenNoEnemiesVisible() {
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseContext().contains(QuarkMindCaseFile.ENEMY_UNITS)).isTrue();
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
        assertThat(intentQueue.drainAll())
            .noneMatch(i -> i instanceof AttackIntent || i instanceof BlinkIntent);
    }

    @Test
    void strategyRequiresScoutingOutputToActivate() {
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseContext().contains(QuarkMindCaseFile.READY)).isTrue();

        var withScouting = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE,
            QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0));
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        assertThat(strategyTask.testActivation(withScouting)).isTrue();
    }

    @Test
    void tacticsActivatesWhenThreatPositionAndStrategyPresent() {
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseContext().contains(QuarkMindCaseFile.ENEMY_UNITS)).isTrue();
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
    }

    @Test
    void firstTickEmitsCorrectDeclineSignals() {
        orchestrator.gameTick();

        List<io.casehub.qhorus.api.message.Message> delta = messageService.pollAfter(
            dispatchBroker.channelId(),
            afterId > 0 ? afterId : null,
            20);

        List<io.casehub.qhorus.api.message.Message> commands =
            delta.stream().filter(m -> m.messageType() == io.casehub.qhorus.api.message.MessageType.COMMAND).toList();
        List<io.casehub.qhorus.api.message.Message> dones =
            delta.stream().filter(m -> m.messageType() == io.casehub.qhorus.api.message.MessageType.DONE).toList();
        List<io.casehub.qhorus.api.message.Message> declines =
            delta.stream().filter(m -> m.messageType() == io.casehub.qhorus.api.message.MessageType.DECLINE).toList();

        assertThat(commands).hasSize(4);
        assertThat(dones).hasSize(2);
        assertThat(declines).hasSize(2);

        assertThat(dones.stream().map(m -> m.sender()).toList())
            .containsExactlyInAnyOrder("plugin:scouting.drools-cep", "plugin:economics.flow");

        assertThat(declines.stream().map(m -> m.sender()).toList())
            .containsExactlyInAnyOrder("plugin:strategy.early-pressure", "plugin:strategy.economic-expansion");
    }
}
