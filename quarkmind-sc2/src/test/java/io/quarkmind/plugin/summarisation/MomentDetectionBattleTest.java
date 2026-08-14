package io.quarkmind.plugin.summarisation;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MomentDetectionBattleTest {

    private TestMomentDetectionTask task;
    private List<GameMoment> emittedMoments;

    @BeforeEach
    void setUp() {
        emittedMoments = new ArrayList<>();
        task = new TestMomentDetectionTask(emittedMoments);
    }

    @Test
    void battleStartsOnArmyValueDrop() {
        List<Unit> army10 = stalkers(10);
        List<Unit> enemy5 = zealots(5);

        task.tickBattle(100, army10, enemy5);
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IDLE);

        List<Unit> army7 = stalkers(7);
        task.tickBattle(200, army7, enemy5);
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IN_BATTLE);
    }

    @Test
    void noBattleWhenArmyValueDropsBelowThreshold() {
        task.tickBattle(100, stalkers(10), zealots(5));
        task.tickBattle(200, stalkers(9), zealots(5));
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IDLE);
    }

    @Test
    void quiescenceThenBattleEnded() {
        List<Unit> army10 = stalkers(10);
        List<Unit> enemy5 = zealots(5);

        task.tickBattle(100, army10, enemy5);
        task.tickBattle(200, stalkers(7), enemy5);
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IN_BATTLE);

        task.tickBattle(300, stalkers(7), enemy5);
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.QUIESCENT);

        task.tickBattle(300 + MomentDetectionTask.QUIESCENCE_FRAMES, stalkers(7), zealots(3));
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IDLE);
        assertThat(emittedMoments).hasSize(1);
        assertThat(emittedMoments.get(0).type()).isEqualTo(GameMomentType.BATTLE_ENDED);

        EngagementOutcome engagement = (EngagementOutcome) emittedMoments.get(0).context().get("engagement");
        assertThat(engagement).isNotNull();
        assertThat(engagement.ownUnitsLost()).isEqualTo(3);
        assertThat(engagement.enemyUnitsLost()).isEqualTo(2);
        assertThat(engagement.outcome()).isEqualTo(EngagementOutcome.Outcome.LOST);
    }

    @Test
    void quiescenceInterruptedByNewFighting() {
        task.tickBattle(100, stalkers(10), zealots(5));
        task.tickBattle(200, stalkers(7), zealots(5));
        task.tickBattle(300, stalkers(7), zealots(5));
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.QUIESCENT);

        task.tickBattle(350, stalkers(5), zealots(5));
        assertThat(task.battleState()).isEqualTo(MomentDetectionTask.BattleState.IN_BATTLE);
        assertThat(emittedMoments).isEmpty();
    }

    @Test
    void multipleSequentialEngagements() {
        task.tickBattle(100, stalkers(10), zealots(5));
        task.tickBattle(200, stalkers(7), zealots(5));
        task.tickBattle(300, stalkers(7), zealots(5));
        task.tickBattle(300 + MomentDetectionTask.QUIESCENCE_FRAMES, stalkers(7), zealots(3));
        assertThat(emittedMoments).hasSize(1);

        task.tickBattle(600, stalkers(7), zealots(3));
        task.tickBattle(700, stalkers(5), zealots(3));
        task.tickBattle(800, stalkers(5), zealots(3));
        task.tickBattle(800 + MomentDetectionTask.QUIESCENCE_FRAMES, stalkers(5), zealots(1));
        assertThat(emittedMoments).hasSize(2);
    }

    static List<Unit> stalkers(int n) {
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            units.add(new Unit("s" + i, UnitType.STALKER, new Point2d(10, 10),
                    160, 160, 80, 80, 0, 0));
        }
        return units;
    }

    static List<Unit> zealots(int n) {
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            units.add(new Unit("z" + i, UnitType.ZEALOT, new Point2d(20, 20),
                    100, 100, 50, 50, 0, 0));
        }
        return units;
    }

    static class TestMomentDetectionTask extends MomentDetectionTask {
        private final List<GameMoment> emitted;

        TestMomentDetectionTask(List<GameMoment> emitted) {
            super(null);
            this.emitted = emitted;
        }

        void tickBattle(long frame, List<Unit> ownArmy, List<Unit> enemyUnits) {
            updateBattleFSM(frame, ownArmy, enemyUnits, emitted);
        }
    }
}
