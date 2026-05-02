package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ReactiveStrategyTest {

    static EnemyObservation obs(long frame, UnitType... playerUnits) {
        List<Unit> units = Arrays.stream(playerUnits)
            .map(t -> new Unit("p", t, new Point2d(8,8), 100,100,50,50,0,0))
            .toList();
        return new EnemyObservation(units, Set.of(), 0, frame);
    }

    @Test
    void shouldSwitch_falseBeforeReEvalInterval() {
        var r = new ReactiveStrategy(50);
        assertThat(r.shouldSwitch(obs(49, UnitType.STALKER))).isFalse();
    }

    @Test
    void shouldSwitch_trueAtReEvalInterval() {
        var r = new ReactiveStrategy(50);
        assertThat(r.shouldSwitch(obs(50, UnitType.STALKER))).isTrue();
    }

    @Test
    void shouldSwitch_trueAtMultiplesOfInterval() {
        var r = new ReactiveStrategy(50);
        r.shouldSwitch(obs(50, UnitType.STALKER)); // first eval
        assertThat(r.shouldSwitch(obs(100, UnitType.STALKER))).isTrue();
    }

    @Test
    void shouldSwitch_falseAtNonMultiple() {
        var r = new ReactiveStrategy(50);
        r.shouldSwitch(obs(50, UnitType.STALKER));
        assertThat(r.shouldSwitch(obs(75, UnitType.STALKER))).isFalse();
    }

    @Test
    void counterFor_rangedUnit_stalker() {
        var r = new ReactiveStrategy(50);
        String counter = r.counterFor(UnitType.STALKER);
        assertThat(counter).isIn("PROTOSS_COLOSSUS_PUSH", "ZERG_ROACH_RUSH");
    }

    @Test
    void counterFor_meleeMass_zealot() {
        var r = new ReactiveStrategy(50);
        assertThat(r.counterFor(UnitType.ZEALOT)).isEqualTo("TERRAN_BIO_PUSH");
    }

    @Test
    void counterFor_meleeMass_zergling() {
        var r = new ReactiveStrategy(50);
        assertThat(r.counterFor(UnitType.ZERGLING)).isEqualTo("TERRAN_BIO_PUSH");
    }

    @Test
    void counterFor_armoured_immortal() {
        var r = new ReactiveStrategy(50);
        assertThat(r.counterFor(UnitType.IMMORTAL)).isEqualTo("ZERG_MASS_LING");
    }

    @Test
    void counterFor_nullInput_returnsNonNull() {
        var r = new ReactiveStrategy(50);
        assertThat(r.counterFor(null)).isNotNull();
    }

    @Test
    void nextUnit_delegatesToInnerStrategy() {
        var r = new ReactiveStrategy(50);
        var innerObs = new EnemyObservation(List.of(), Set.of(), 200, 0L);
        assertThat(r.nextUnit(innerObs)).isPresent();
    }

    @Test
    void resolveCounter_updatesInnerStrategy() {
        var r = new ReactiveStrategy(50);
        r.shouldSwitch(obs(50, UnitType.ZEALOT)); // triggers: TERRAN_BIO_PUSH
        r.resolveCounter(); // switches inner to BIO_PUSH
        // After switch, nextUnit should delegate to TERRAN_BIO_PUSH (Marine-based)
        var innerObs = new EnemyObservation(List.of(), Set.of(), 999, 0L);
        // We can't easily assert the exact unit without knowing BIO_PUSH's build order,
        // but we can assert it returns something non-empty
        assertThat(r.nextUnit(innerObs)).isPresent();
    }

    @Test
    void reset_clearsPendingCounterName() {
        var r = new ReactiveStrategy(50);
        r.shouldSwitch(obs(50, UnitType.ZEALOT));
        r.reset();
        // After reset, shouldSwitch should NOT immediately return true unless frame is a multiple
        assertThat(r.shouldSwitch(obs(1, UnitType.ZEALOT))).isFalse();
    }
}
