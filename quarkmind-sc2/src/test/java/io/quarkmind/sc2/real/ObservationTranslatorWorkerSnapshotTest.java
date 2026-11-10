package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.unit.Unit;
import io.quarkmind.domain.WorkerAction;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTranslatorWorkerSnapshotTest {

    private static final int PROBE_TYPE_ID = 84;
    private static final int HARVEST_GATHER_PROBE_ABILITY = 298;
    private static final int HARVEST_RETURN_PROBE_ABILITY = 299;
    private static final int CARRY_MINERAL_BUFF = 271;
    private static final int CARRY_HIGH_YIELD_MINERAL_BUFF = 272;
    private static final int CARRY_GAS_BUFF = 273;
    private static final int CARRY_GAS_PROTOSS_BUFF = 274;

    @Test
    void workerGatheringMinerals() {
        var proto = workerWithOrder(HARVEST_GATHER_PROBE_ABILITY);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_MINERALS);
        assertThat(snapshot.carrying()).isFalse();
    }

    @Test
    void workerReturningMineralsWithCarryBuff() {
        var proto = workerWithOrderAndBuff(HARVEST_RETURN_PROBE_ABILITY, CARRY_MINERAL_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void workerReturningHighYieldMinerals() {
        var proto = workerWithOrderAndBuff(HARVEST_RETURN_PROBE_ABILITY, CARRY_HIGH_YIELD_MINERAL_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void workerGatheringGas() {
        var proto = workerWithOrderAndBuff(HARVEST_GATHER_PROBE_ABILITY, CARRY_GAS_PROTOSS_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void workerReturningGas() {
        var proto = workerWithOrderAndBuff(HARVEST_RETURN_PROBE_ABILITY, CARRY_GAS_PROTOSS_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void workerReturningGenericGasBuff() {
        var proto = workerWithOrderAndBuff(HARVEST_RETURN_PROBE_ABILITY, CARRY_GAS_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void idleWorker() {
        var proto = workerWithNoOrders();
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.IDLE);
        assertThat(snapshot.carrying()).isFalse();
    }

    @Test
    void extractsTagAndPosition() {
        var proto = SC2APIProtocol.Raw.Unit.newBuilder()
            .setTag(42)
            .setUnitType(PROBE_TYPE_ID)
            .setAlliance(SC2APIProtocol.Raw.Alliance.Self)
            .setPos(SC2APIProtocol.Common.Point.newBuilder().setX(10.5f).setY(20.3f).setZ(0))
            .setDisplayType(SC2APIProtocol.Raw.DisplayType.Visible)
            .setOwner(1)
            .build();
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.tag()).isEqualTo("42");
        assertThat(snapshot.position().x()).isEqualTo(10.5f);
        assertThat(snapshot.position().y()).isEqualTo(20.3f);
    }

    @Test
    void harvestReturnWithoutCarryBuffIsReturningMinerals() {
        var proto = workerWithOrder(HARVEST_RETURN_PROBE_ABILITY);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(snapshot.carrying()).isFalse();
    }

    @Test
    void harvestGatherWithGasBuffIsGatheringGas() {
        var proto = workerWithOrderAndBuff(HARVEST_GATHER_PROBE_ABILITY, CARRY_GAS_BUFF);
        Unit unit = Unit.from(proto);

        WorkerSnapshot snapshot = ObservationTranslator.toWorkerSnapshot(unit);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    private static SC2APIProtocol.Raw.Unit workerWithOrder(int abilityId) {
        return SC2APIProtocol.Raw.Unit.newBuilder()
            .setTag(1)
            .setUnitType(PROBE_TYPE_ID)
            .setAlliance(SC2APIProtocol.Raw.Alliance.Self)
            .setPos(SC2APIProtocol.Common.Point.newBuilder().setX(5).setY(5).setZ(0))
            .setDisplayType(SC2APIProtocol.Raw.DisplayType.Visible)
            .setOwner(1)
            .addOrders(SC2APIProtocol.Raw.UnitOrder.newBuilder().setAbilityId(abilityId))
            .build();
    }

    private static SC2APIProtocol.Raw.Unit workerWithOrderAndBuff(int abilityId, int buffId) {
        return SC2APIProtocol.Raw.Unit.newBuilder()
            .setTag(1)
            .setUnitType(PROBE_TYPE_ID)
            .setAlliance(SC2APIProtocol.Raw.Alliance.Self)
            .setPos(SC2APIProtocol.Common.Point.newBuilder().setX(5).setY(5).setZ(0))
            .setDisplayType(SC2APIProtocol.Raw.DisplayType.Visible)
            .setOwner(1)
            .addOrders(SC2APIProtocol.Raw.UnitOrder.newBuilder().setAbilityId(abilityId))
            .addBuffIds(buffId)
            .build();
    }

    private static SC2APIProtocol.Raw.Unit workerWithNoOrders() {
        return SC2APIProtocol.Raw.Unit.newBuilder()
            .setTag(1)
            .setUnitType(PROBE_TYPE_ID)
            .setAlliance(SC2APIProtocol.Raw.Alliance.Self)
            .setPos(SC2APIProtocol.Common.Point.newBuilder().setX(5).setY(5).setZ(0))
            .setDisplayType(SC2APIProtocol.Raw.DisplayType.Visible)
            .setOwner(1)
            .build();
    }
}
