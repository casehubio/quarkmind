package io.quarkmind.sc2.mock;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayEnemyPositionUpdateTest {

    private static final Path REPLAY = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    @Test
    void enemyUnitPositionsChangeAcrossTicks() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);

        // Advance to tick 260 — just past the first UnitPositions events at loop 5760
        for (int i = 0; i < 260; i++) {game.tick();}

        GameState before = game.snapshot();
        assertThat(before.enemyUnits()).as("enemies visible by tick 260").isNotEmpty();

        Map<String, Point2d> positionsBefore = new HashMap<>();
        for (Unit u : before.enemyUnits()) {
            positionsBefore.put(u.tag(), u.position());
        }

        // Advance another 200 ticks — covers many UnitPositions events
        for (int i = 0; i < 200; i++) {game.tick();}

        GameState after = game.snapshot();

        // Check all units: both survivors from before AND newly born units that got position updates
        boolean anyMoved = false;
        for (Unit u : after.enemyUnits()) {
            Point2d oldPos = positionsBefore.get(u.tag());
            if (oldPos != null && !oldPos.equals(u.position())) {
                anyMoved = true;
                break;
            }
        }
        assertThat(anyMoved)
                .as("at least one enemy unit should move between tick 260 and 460")
                .isTrue();
    }
}
