package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnitTypeWorkerTest {

    @Test void probe_isWorker() { assertThat(UnitType.PROBE.isWorker()).isTrue(); }
    @Test void scv_isWorker()   { assertThat(UnitType.SCV.isWorker()).isTrue(); }
    @Test void drone_isWorker() { assertThat(UnitType.DRONE.isWorker()).isTrue(); }

    @Test void zealot_isNotWorker()   { assertThat(UnitType.ZEALOT.isWorker()).isFalse(); }
    @Test void marine_isNotWorker()   { assertThat(UnitType.MARINE.isWorker()).isFalse(); }
    @Test void zergling_isNotWorker() { assertThat(UnitType.ZERGLING.isWorker()).isFalse(); }
    @Test void mule_isNotWorker()     { assertThat(UnitType.MULE.isWorker()).isFalse(); }
    @Test void overlord_isNotWorker() { assertThat(UnitType.OVERLORD.isWorker()).isFalse(); }
    @Test void observer_isNotWorker() { assertThat(UnitType.OBSERVER.isWorker()).isFalse(); }
}
