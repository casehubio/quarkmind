package io.quarkmind.plugin.summarisation;

import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import jakarta.enterprise.inject.Vetoed;
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

    @Test
    void battleEndedWithEmptyPendingIntel_doesNotThrowOnImmutableList() {
        // Regression: fireRules() returned List.of() (immutable) when pendingIntel was empty,
        // causing UnsupportedOperationException when updateBattleFSM tried to add BATTLE_ENDED.
        MomentDetectionTask realTask = new MomentDetectionTask(null);

        // Drive the FSM through IDLE → IN_BATTLE → QUIESCENT → BATTLE_ENDED via execute()
        io.quarkmind.agency.context.MutableMapCaseContext ctx = new io.quarkmind.agency.context.MutableMapCaseContext(
                new java.util.HashMap<>(java.util.Map.of(
                        QuarkMindCaseFile.GAME_FRAME, 100L,
                        QuarkMindCaseFile.SUPPLY_USED, 30,
                        QuarkMindCaseFile.SUPPLY_CAP, 46,
                        QuarkMindCaseFile.ARMY, stalkers(10),
                        QuarkMindCaseFile.ENEMY_UNITS, zealots(5),
                        QuarkMindCaseFile.ENEMY_POSTURE, "MACRO",
                        QuarkMindCaseFile.TIMING_ATTACK_INCOMING, false
                                                        )));
        realTask.execute(ctx); // tick 1: baseline

        ctx.set(QuarkMindCaseFile.GAME_FRAME, 200L);
        ctx.set(QuarkMindCaseFile.ARMY, stalkers(7));
        realTask.execute(ctx); // tick 2: battle starts

        ctx.set(QuarkMindCaseFile.GAME_FRAME, 300L);
        realTask.execute(ctx); // tick 3: quiescent

        ctx.set(QuarkMindCaseFile.GAME_FRAME, (long) (300 + MomentDetectionTask.QUIESCENCE_FRAMES));
        ctx.set(QuarkMindCaseFile.ENEMY_UNITS, zealots(3));
        realTask.execute(ctx); // tick 4: BATTLE_ENDED — would throw before fix

        assertThat(realTask.battleState()).isEqualTo(MomentDetectionTask.BattleState.IDLE);
        @SuppressWarnings("unchecked")
        List<GameMoment> moments = (List<GameMoment>) ctx.get(QuarkMindCaseFile.MOMENTS_LATEST);
        assertThat(moments).isNotNull();
        assertThat(moments).extracting(GameMoment::type).contains(GameMomentType.BATTLE_ENDED);
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

    @Vetoed
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
