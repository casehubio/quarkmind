package io.quarkmind.plugin;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.cbr.SC2StrategyRouterTask;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Drools strategy rules.
 *
 * <p>Requires {@code @QuarkusTest} — {@link DroolsStrategyTask} uses {@code drools-quarkus}
 * whose {@code DataSource.createStore()} factory is initialised at Quarkus build time and is
 * unavailable in plain JUnit (see GE-0053). Tests call {@link DroolsStrategyTask#execute}
 * directly with a populated {@link MutableMapCaseContext}.
 */
@QuarkusTest
class DroolsStrategyTaskTest {

    @Inject DroolsStrategyTask strategyTask;
    @Inject SC2StrategyRouterTask strategyRouter;
    @Inject IntentQueue intentQueue;
    @Inject ScoutingIntelBroker broker;

    @BeforeEach
    @AfterEach
    void drainQueue() {
        intentQueue.drainAll();
        broker.clearLatest();
        // Router fallback is strategy.drools when broker has no archetype
        strategyTask.resetPrevStrategy(); // prevStrategy leaks across @Test methods on the same CDI bean
    }

    // --- Gateway ---

    @Test
    void buildsGatewayWhenPylonExistsAndMineralsAvailable() {
        var ctx = caseContext(200, 0, workers(6), List.of(nexus(), completePylon()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithoutPylon() {
        var ctx = caseContext(200, 0, workers(6), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayIfAlreadyExists() {
        var ctx = caseContext(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithInsufficientMinerals() {
        var ctx = caseContext(100, 0, workers(6), List.of(nexus(), completePylon()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    // --- CyberneticsCore ---

    @Test
    void buildsCyberneticsCoreWhenGatewayCompleteAndMineralsAvailable() {
        var ctx = caseContext(200, 0, workers(6), List.of(nexus(), completePylon(), gateway(true)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCorIfGatewayNotComplete() {
        var ctx = caseContext(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCoreIfAlreadyExists() {
        var ctx = caseContext(300, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(false)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    // --- Stalker training ---

    @Test
    void trainsStalkerWhenCoreAndGatewayCompleteAndGasAvailable() {
        var ctx = caseContext(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutGas() {
        var ctx = caseContext(200, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutCyberneticsCore() {
        var ctx = caseContext(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true)), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    // --- Strategy assessment (C2 — posture-driven) ---

    @Test
    void strategyIsDefendWhenAllInPosture() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("ALL_IN"));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "ALL_IN", false);
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("DEFEND");
    }

    @Test
    void strategyIsDefendWhenTimingAttackIncoming() {
        broker.update(new ScoutingIntelPayload.TimingAlert(true));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "UNKNOWN", true);
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("DEFEND");
    }

    @Test
    void strategyIsDefendWhenTimingAttackIncomingWithStalkers() {
        broker.update(new ScoutingIntelPayload.TimingAlert(true));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "UNKNOWN", true);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("DEFEND");
    }

    @Test
    void strategyIsDefendNotAttackWhenAllInPostureWithStalkers() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("ALL_IN"));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "ALL_IN", false);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("DEFEND");
    }

    @Test
    void strategyIsAttackWhenMacroPostureAndEnoughStalkers() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("MACRO"));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "MACRO", false);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }

    @Test
    void strategyIsAttackWhenUnknownPostureAndEnoughStalkers() {
        // UNKNOWN is the default when broker is empty — no broker.update() needed
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "UNKNOWN", false);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }

    @Test
    void strategyIsMacroWhenNoIntelAndNoArmy() {
        // Empty broker → posture defaults to "UNKNOWN", timing to false
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("MACRO");
    }

    @Test
    void strategyIsMacroWhenBelowAttackThresholdWithMacroPosture() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("MACRO"));
        var ctx = caseContext(50, 0, workers(12), List.of(nexus()), "MACRO", false);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(3));
        strategyTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("MACRO");
    }

    // --- Subscription hot-reload ---

    @Test
    void refreshSubscriptions_updatesSubscribedTypes() {
        // DroolsStrategyTask implements ScoutingIntelConsumer — cast to verify subscription state
        // @PostConstruct ran; defaults: POSTURE and TIMING_ALERT (BUILD_ORDER deferred)
        var consumer = (io.quarkmind.agent.plugin.ScoutingIntelConsumer) strategyTask;
        assertThat(consumer.subscribedIntelTypes())
            .containsExactlyInAnyOrder(
                ScoutingIntelType.POSTURE,
                ScoutingIntelType.TIMING_ALERT,
                ScoutingIntelType.PATTERN_ASSESSMENT);
    }

    // --- Entry criteria — two-gate model: {READY, ENEMY_ARMY_SIZE} + broker.current(POSTURE) ---

    @Test
    void testActivation_true_whenBothGatesSatisfied() {
        // Gate 1: context has {READY, ENEMY_ARMY_SIZE}; Gate 2: STRATEGY_SELECTED_ID matches
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY,           Boolean.TRUE,
            QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0,
            QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools"));
        assertThat(strategyTask.testActivation(ctx)).isTrue();
    }

    @Test
    void testActivation_false_whenReadyAbsent() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0));  // READY is missing
        assertThat(strategyTask.testActivation(ctx)).isFalse();
    }

    @Test
    void testActivation_false_whenEnemyArmySizeAbsent() {
        // ENEMY_ARMY_SIZE is the ordering dependency — strategy can't run until scouting has
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE));  // ENEMY_ARMY_SIZE is missing
        assertThat(strategyTask.testActivation(ctx)).isFalse();
    }

    @Test
    void testActivation_false_whenBrokerHasNoPosture() {
        // Context gates satisfied but broker is empty — intel gate fails
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY,           Boolean.TRUE,
            QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0));
        assertThat(strategyTask.testActivation(ctx)).isFalse();
    }

    // --- Gateway (coverage migrated from BasicStrategyTaskTest) ---

    @Test
    void doesNotBuildGatewayWhenPylonIsUnderConstruction() {
        var ctx = caseContext(200, 0, workers(6), List.of(nexus(), incompletePylon()), "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    // --- #173: Assimilator dispatch ---

    @Test
    void buildsAssimilatorWhenGatewayCompleteAndFreeGeyserAndMineralsAvailable() {
        var geyserPos = new Point2d(30, 30);
        var ctx = caseContextWithGeysers(75, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true)),
            List.of(geyser(geyserPos)),
            "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    @Test
    void doesNotBuildAssimilatorWithInsufficientMinerals() {
        var ctx = caseContextWithGeysers(50, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true)),
            List.of(geyser(new Point2d(30, 30))),
            "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    @Test
    void doesNotBuildAssimilatorWhenNoFreeGeyserExists() {
        var geyserPos = new Point2d(30, 30);
        var ctx = caseContextWithGeysers(200, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), assimilator(geyserPos)),
            List.of(geyser(geyserPos)),
            "UNKNOWN", false);
        strategyTask.execute(ctx);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    // --- Helpers ---

    /** Posture-driven context helper — uses scouting-derived intel, not raw enemies. */
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
            Map.entry(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, timingAttack),
            Map.entry(QuarkMindCaseFile.GAME_FRAME,             500L)
        ));
    }


    private List<Unit> workers(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Unit("p-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0, 0))
            .toList();
    }

    private List<Unit> stalkers(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Unit("s-" + i, UnitType.STALKER, new Point2d(10, 10), 80, 80, 80, 80, 0, 0))
            .toList();
    }

    private Building nexus()                     { return bldg("n-0",   BuildingType.NEXUS,             true);  }
    private Building completePylon()             { return bldg("py-0",  BuildingType.PYLON,             true);  }
    private Building incompletePylon()           { return bldg("py-inc",BuildingType.PYLON,             false); }
    private Building gateway(boolean c)          { return bldg("gw-0",  BuildingType.GATEWAY,           c); }
    private Building cyberneticsCore(boolean c)  { return bldg("cc-0",  BuildingType.CYBERNETICS_CORE,  c); }

    private Building bldg(String tag, BuildingType type, boolean complete) {
        return new Building(tag, type, new Point2d(10, 10), 500, 500, complete);
    }

    private Building assimilator(Point2d pos) {
        return new Building("as-0", BuildingType.ASSIMILATOR, pos, 400, 400, true);
    }

    private Resource geyser(Point2d pos) {
        return new Resource("g-0", pos, 2250);
    }

    private MutableMapCaseContext caseContextWithGeysers(int minerals, int vespene, List<Unit> workers,
                                          List<Building> buildings, List<Resource> geysers,
                                          String enemyPosture, boolean timingAttack) {
        var ctx = caseContext(minerals, vespene, workers, buildings, enemyPosture, timingAttack);
        ctx.set(QuarkMindCaseFile.GEYSERS, geysers);
        return ctx;
    }

}
