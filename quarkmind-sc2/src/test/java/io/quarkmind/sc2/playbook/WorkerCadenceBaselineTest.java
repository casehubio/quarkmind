package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.WorkerAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerCadenceBaselineTest {

    @Test
    void jsonRoundTrip() {
        var w1 = new WorkerSnapshot("100", new Point2d(10.5f, 20.3f), WorkerAction.GATHERING_MINERALS, false);
        var w2 = new WorkerSnapshot("101", new Point2d(12.0f, 18.0f), WorkerAction.RETURNING_MINERALS, true);
        var cadence = List.of(
                new WorkerCadenceSnapshot(0, List.of(w1, w2)),
                new WorkerCadenceSnapshot(1, List.of(w1, w2))
        );
        var baseline = new WorkerCadenceBaseline(
                "cal-mineral-baseline-12w", "PROTOSS", "5.0.16",
                "2026-11-10T00:00:00Z", 2, cadence
        );

        String json = baseline.toJson();
        WorkerCadenceBaseline restored = WorkerCadenceBaseline.fromJson(json);

        assertThat(restored.playbook()).isEqualTo("cal-mineral-baseline-12w");
        assertThat(restored.race()).isEqualTo("PROTOSS");
        assertThat(restored.tickCount()).isEqualTo(2);
        assertThat(restored.cadence()).hasSize(2);

        WorkerSnapshot restoredW1 = restored.cadence().get(0).workers().get(0);
        assertThat(restoredW1.tag()).isEqualTo("100");
        assertThat(restoredW1.position().x()).isEqualTo(10.5f);
        assertThat(restoredW1.action()).isEqualTo(WorkerAction.GATHERING_MINERALS);
        assertThat(restoredW1.carrying()).isFalse();

        WorkerSnapshot restoredW2 = restored.cadence().get(0).workers().get(1);
        assertThat(restoredW2.tag()).isEqualTo("101");
        assertThat(restoredW2.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(restoredW2.carrying()).isTrue();
    }
}
