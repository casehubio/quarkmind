package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
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
 * unavailable in plain JUnit (see GE-0053). Tests call {@link StrategyTask#execute(io.casehub.core.CaseFile)}
 * directly with a populated {@link CaseFile}.
 */
@QuarkusTest
class DroolsStrategyTaskTest {

    @Inject @CaseType("starcraft-game") StrategyTask strategyTask;
    @Inject IntentQueue intentQueue;
    @Inject ScoutingIntelBroker broker;

    @BeforeEach
    @AfterEach
    void drainQueue() {
        intentQueue.drainAll();
        broker.clearLatest();
    }

    // --- Gateway ---

    @Test
    void buildsGatewayWhenPylonExistsAndMineralsAvailable() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus(), completePylon()), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithoutPylon() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayIfAlreadyExists() {
        var cf = caseFile(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithInsufficientMinerals() {
        var cf = caseFile(100, 0, workers(6), List.of(nexus(), completePylon()), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    // --- CyberneticsCore ---

    @Test
    void buildsCyberneticsCoreWhenGatewayCompleteAndMineralsAvailable() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus(), completePylon(), gateway(true)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCorIfGatewayNotComplete() {
        var cf = caseFile(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCoreIfAlreadyExists() {
        var cf = caseFile(300, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(false)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    // --- Stalker training ---

    @Test
    void trainsStalkerWhenCoreAndGatewayCompleteAndGasAvailable() {
        var cf = caseFile(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutGas() {
        var cf = caseFile(200, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutCyberneticsCore() {
        var cf = caseFile(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true)), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    // --- Strategy assessment (C2 — posture-driven) ---

    @Test
    void strategyIsDefendWhenAllInPosture() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("ALL_IN"));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "ALL_IN", false);
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("DEFEND");
    }

    @Test
    void strategyIsDefendWhenTimingAttackIncoming() {
        broker.update(new ScoutingIntelPayload.TimingAlert(true));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "UNKNOWN", true);
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("DEFEND");
    }

    @Test
    void strategyIsDefendWhenTimingAttackIncomingWithStalkers() {
        broker.update(new ScoutingIntelPayload.TimingAlert(true));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "UNKNOWN", true);
        cf.put(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("DEFEND");
    }

    @Test
    void strategyIsDefendNotAttackWhenAllInPostureWithStalkers() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("ALL_IN"));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "ALL_IN", false);
        cf.put(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("DEFEND");
    }

    @Test
    void strategyIsAttackWhenMacroPostureAndEnoughStalkers() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("MACRO"));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "MACRO", false);
        cf.put(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("ATTACK");
    }

    @Test
    void strategyIsAttackWhenUnknownPostureAndEnoughStalkers() {
        // UNKNOWN is the default when broker is empty — no broker.update() needed
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "UNKNOWN", false);
        cf.put(QuarkMindCaseFile.ARMY, stalkers(4));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("ATTACK");
    }

    @Test
    void strategyIsMacroWhenNoIntelAndNoArmy() {
        // Empty broker → posture defaults to "UNKNOWN", timing to false
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("MACRO");
    }

    @Test
    void strategyIsMacroWhenBelowAttackThresholdWithMacroPosture() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("MACRO"));
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), "MACRO", false);
        cf.put(QuarkMindCaseFile.ARMY, stalkers(3));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("MACRO");
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
                ScoutingIntelType.TIMING_ALERT);
    }

    // --- Entry criteria — two-gate model: {READY, ENEMY_ARMY_SIZE} + broker.current(POSTURE) ---

    @Test
    void canActivate_true_whenBothGatesSatisfied() {
        // Gate 1: CaseFile has {READY, ENEMY_ARMY_SIZE}; Gate 2: broker has POSTURE
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY,           Boolean.TRUE);
        cf.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);
        assertThat(strategyTask.canActivate(cf)).isTrue();
    }

    @Test
    void canActivate_false_whenReadyAbsent() {
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);  // READY is missing
        assertThat(strategyTask.canActivate(cf)).isFalse();
    }

    @Test
    void canActivate_false_whenEnemyArmySizeAbsent() {
        // ENEMY_ARMY_SIZE is the ordering dependency — strategy can't run until scouting has
        broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN"));
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY, Boolean.TRUE);  // ENEMY_ARMY_SIZE is missing
        assertThat(strategyTask.canActivate(cf)).isFalse();
    }

    @Test
    void canActivate_false_whenBrokerHasNoPosture() {
        // CaseFile gates satisfied but broker is empty — intel gate fails
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY,           Boolean.TRUE);
        cf.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);
        assertThat(strategyTask.canActivate(cf)).isFalse();
    }

    // --- Gateway (coverage migrated from BasicStrategyTaskTest) ---

    @Test
    void doesNotBuildGatewayWhenPylonIsUnderConstruction() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus(), incompletePylon()), "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    // --- #173: Assimilator dispatch ---

    @Test
    void buildsAssimilatorWhenGatewayCompleteAndFreeGeyserAndMineralsAvailable() {
        var geyserPos = new Point2d(30, 30);
        var cf = caseFileWithGeysers(75, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true)),
            List.of(geyser(geyserPos)),
            "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    @Test
    void doesNotBuildAssimilatorWithInsufficientMinerals() {
        var cf = caseFileWithGeysers(50, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true)),
            List.of(geyser(new Point2d(30, 30))),
            "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    @Test
    void doesNotBuildAssimilatorWhenNoFreeGeyserExists() {
        var geyserPos = new Point2d(30, 30);
        var cf = caseFileWithGeysers(200, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), assimilator(geyserPos)),
            List.of(geyser(geyserPos)),
            "UNKNOWN", false);
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR))
            .isTrue();
    }

    // --- Helpers ---

    /** Posture-driven CaseFile helper — uses scouting-derived intel, not raw enemies. */
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

    private CaseFile caseFileWithGeysers(int minerals, int vespene, List<Unit> workers,
                                          List<Building> buildings, List<Resource> geysers,
                                          String enemyPosture, boolean timingAttack) {
        var cf = caseFile(minerals, vespene, workers, buildings, enemyPosture, timingAttack);
        cf.put(QuarkMindCaseFile.GEYSERS, geysers);
        return cf;
    }

}
