package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates EmulatedGame economic accuracy against real SC2 replay data.
 *
 * <p>Strategy:
 * <ul>
 *   <li>Buildings are injected from replay tracker events (ground truth) into EmulatedGame
 *       at each tick, using the same replay tags. This allows TrainIntents (which carry
 *       replay building tags) to be applied directly without tag remapping.</li>
 *   <li>Resources (minerals, vespene) are NOT synced — divergence here is expected given
 *       EmulatedGame's flat mining rate vs SC2's saturation-based model.</li>
 *   <li>Mining probe count IS synced so the resource model uses realistic input.</li>
 *   <li>Unit count divergence measures train command extraction accuracy.</li>
 * </ul>
 */
public final class ReplayValidationHarness {

    /** Game loops per tick — 22 at SC2 Faster speed. */
    private static final int LOOPS_PER_TICK = 22;

    private ReplayValidationHarness() {}

    /**
     * Runs the validation harness.
     *
     * @param replayPath  path to a .SC2Replay file
     * @param playerId    1-indexed player to validate
     * @param tickLimit   maximum ticks to run (use Integer.MAX_VALUE for full replay)
     * @return divergence report comparing emulated vs ground-truth state at each tick
     */
    public static DivergenceReport run(Path replayPath, int playerId, int tickLimit) {
        ReplaySimulatedGame replayGame = new ReplaySimulatedGame(replayPath, playerId);
        EmulatedGame        emulated   = new EmulatedGame();

        replayGame.reset();
        emulated.reset();

        assertInitialStateMatch(replayGame.snapshot(), emulated.snapshot(), replayPath, playerId);

        ReplayCommandStream commands = ReplayCommandExtractor.extract(replayPath, playerId);
        List<TimedIntent>   intents  = commands.intents();
        int                 cursor   = 0;

        // Tracks which building tags have already been injected into EmulatedGame
        Set<String> injectedTags = new HashSet<>();

        List<DivergenceReport.TickSnapshot> snapshots = new ArrayList<>(Math.min(tickLimit, 10000));

        for (int tick = 0; tick < tickLimit && !replayGame.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * LOOPS_PER_TICK;

            // Sync probe count from current GT.
            // MINERALS_PER_PROBE_PER_TICK is calibrated per-game-loop, not per replay tick,
            // so multiply by LOOPS_PER_TICK to produce the correct per-replay-tick income.
            // trainTimeInTicks values are in outer ticks, so emulated.tick() is called once —
            // both systems stay consistent with the original EmulatedGame scheduling model.
            GameState gtBefore = replayGame.snapshot();
            emulated.setMiningProbes(countProbes(gtBefore) * LOOPS_PER_TICK);

            emulated.tick();
            replayGame.tick();

            GameState gt = replayGame.snapshot();

            // Sync buildings from post-tick GT into EmulatedGame.
            // Buildings that became complete this tick are also updated.
            syncBuildings(emulated, gt, injectedTags);

            // Sync supply from GT: the real player builds Pylons we can't reconstruct.
            // Without this, training would be blocked by the initial 15-supply cap.
            if (gt.supply() > 0) {
                emulated.setSupplyCapForHarness(gt.supply());
            }

            // Apply TrainIntents for this tick window after ticking.
            // Post-tick application matches the original harness timing: intents see the
            // post-tick gameFrame, so completion timing aligns with replay ground truth.
            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor).intent());
                cursor++;
            }

            GameState em = emulated.snapshot();

            snapshots.add(new DivergenceReport.TickSnapshot(
                tick,
                gt.myUnits().size(),     em.myUnits().size(),
                gt.myBuildings().size(), em.myBuildings().size(),
                gt.minerals(),           em.minerals(),
                gt.vespene(),            em.vespene()));
        }

        return DivergenceReport.from(snapshots);
    }

    /**
     * Syncs buildings from the replay ground truth into EmulatedGame.
     * Injects any building whose tag has not yet been seen.
     * Marks buildings complete when ground truth says they are finished.
     */
    private static void syncBuildings(EmulatedGame emulated, GameState gt, Set<String> injectedTags) {
        for (Building gtBuilding : gt.myBuildings()) {
            if (!injectedTags.contains(gtBuilding.tag())) {
                emulated.injectReplayBuilding(gtBuilding);
                injectedTags.add(gtBuilding.tag());
            } else if (gtBuilding.isComplete()) {
                // Building may have finished construction this tick — ensure EmulatedGame reflects it
                emulated.markReplayBuildingComplete(gtBuilding.tag());
            }
        }
    }

    /** Counts Probe units in a GameState snapshot. */
    private static int countProbes(GameState state) {
        return (int) state.myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE)
            .count();
    }

    /**
     * Checks structural initial state (unit counts only).
     * Buildings are intentionally excluded: ReplaySimulatedGame.reset() processes loop-0
     * tracker events, but the Nexus tracker event may arrive at a different loop than 0,
     * so initial building counts can differ by 1. This resolves after the first tick.
     * Minerals are excluded: the first PlayerStats event arrives at loop 22, so the replay
     * starts with 0 minerals while EmulatedGame seeds 50.
     */
    private static void assertInitialStateMatch(GameState gt, GameState em,
                                                 Path replayPath, int playerId) {
        if (gt.myUnits().size() != em.myUnits().size()) {
            throw new IllegalStateException(String.format(
                "Initial unit count mismatch for %s player %d — replay: %d, emulated: %d",
                replayPath.getFileName(), playerId, gt.myUnits().size(), em.myUnits().size()));
        }
    }
}
