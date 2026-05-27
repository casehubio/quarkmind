package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.mock.SimulatedGame;
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
 *   <li>Minerals are NOT synced — divergence is expected given EmulatedGame's flat mining
 *       rate vs SC2's saturation-based model.</li>
 *   <li>Vespene IS synced from pre-tick ground truth so gas-unit TrainIntents can succeed
 *       with the same resource availability as the real player. See #148.</li>
 *   <li>Mining probe count IS synced so the resource model uses realistic input.</li>
 *   <li>Unit count divergence measures train command extraction accuracy.</li>
 * </ul>
 */
public final class ReplayValidationHarness {

    private ReplayValidationHarness() {}

    /**
     * Runs the validation harness against any SimulatedGame.
     *
     * @param groundTruth ground-truth game state (e.g., ReplaySimulatedGame or IEM10JsonSimulatedGame)
     * @param intents     list of timed intents to apply
     * @param tickLimit   maximum ticks to run
     * @return divergence report comparing emulated vs ground-truth state at each tick
     */
    public static DivergenceReport run(SimulatedGame groundTruth, List<TimedIntent> intents, int tickLimit) {
        EmulatedGame emulated = new EmulatedGame();

        groundTruth.reset();
        emulated.reset();

        assertInitialStateMatch(groundTruth.snapshot(), emulated.snapshot(), groundTruth.getClass().getSimpleName());

        int cursor = 0;

        // Tracks which building tags have already been injected into EmulatedGame
        Set<String> injectedTags = new HashSet<>();

        List<DivergenceReport.TickSnapshot> snapshots = new ArrayList<>(Math.min(tickLimit, 10000));

        for (int tick = 0; tick < tickLimit && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            // Sync probe count from current GT so the saturation model produces realistic
            // per-outer-tick income. SC2Data.mineralIncomePerTick handles the per-tick
            // rate internally; the raw probe count is the correct input.
            GameState gtBefore = groundTruth.snapshot();
            emulated.setMiningProbesPerBase(countProbesPerBase(gtBefore));

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();

            // Sync buildings from post-tick GT into EmulatedGame.
            // Buildings that became complete this tick are also updated.
            syncBuildings(emulated, gt, injectedTags);

            // Sync supply (post-tick GT) and vespene (pre-tick GT): the real player builds
            // Pylons we can't reconstruct (supply), and their vespene BEFORE issuing commands
            // is what's available for train intents. Supply uses post-tick because Pylons
            // complete mid-tick; vespene uses pre-tick because it precedes train-command
            // deduction. Without these syncs, training would be blocked or gas-unit train
            // commands would be rejected. See #148.
            if (gt.supply() > 0) {
                emulated.setSupplyCapForHarness(gt.supply());
            }
            emulated.setVespeneForHarness(gtBefore.vespene());

            // Apply TrainIntents for this tick window after ticking.
            // Post-tick application matches the original harness timing: intents see the
            // post-tick gameFrame, so completion timing aligns with replay ground truth.
            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
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
     * Runs the validation harness against a replay file.
     *
     * @param replayPath  path to a .SC2Replay file
     * @param playerId    1-indexed player to validate
     * @param tickLimit   maximum ticks to run (use Integer.MAX_VALUE for full replay)
     * @return divergence report comparing emulated vs ground-truth state at each tick
     */
    public static DivergenceReport run(Path replayPath, int playerId, int tickLimit) {
        ReplaySimulatedGame game    = new ReplaySimulatedGame(replayPath, playerId);
        List<TimedIntent>   intents = ReplayCommandExtractor.extract(replayPath, playerId).intents();
        return run(game, intents, tickLimit);
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

    /** Counts Probe units per base, assigning each probe to its nearest complete Nexus. */
    static int[] countProbesPerBase(GameState state) {
        return EmulatedGame.countProbesPerBase(state.myBuildings(), state.myUnits());
    }

    /**
     * Checks structural initial state (unit counts only).
     * Buildings are intentionally excluded: ReplaySimulatedGame.reset() processes loop-0
     * tracker events, but the Nexus tracker event may arrive at a different loop than 0,
     * so initial building counts can differ by 1. This resolves after the first tick.
     * Minerals are excluded: the first PlayerStats event arrives at loop 22, so the replay
     * starts with 0 minerals while EmulatedGame seeds 50.
     * Unit count allows ±1 tolerance: some IEM10 replays emit an extra UnitBorn at loop 0
     * (e.g. a 13th probe from a ProcessParentRequest event) that resolves by the first tick.
     * A discrepancy larger than 1 indicates a genuine parsing or reset bug.
     */
    private static void assertInitialStateMatch(GameState gt, GameState em, String label) {
        if (Math.abs(gt.myUnits().size() - em.myUnits().size()) > 1) {
            throw new IllegalStateException(String.format(
                "Initial unit count mismatch for %s — replay: %d, emulated: %d",
                label, gt.myUnits().size(), em.myUnits().size()));
        }
    }
}
