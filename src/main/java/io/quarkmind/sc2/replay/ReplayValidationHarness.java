package io.quarkmind.sc2.replay;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs EmulatedGame and ReplaySimulatedGame in parallel, feeding TrainIntents
 * from GAME_EVENTS into the emulated engine and comparing state tick-by-tick.
 *
 * <p>Buildings are NOT synced — bot build commands use abilLink=42 (Smart),
 * indistinguishable from movement, so BuildIntent extraction is unreliable.
 * Building count divergence is recorded but not asserted in the regression test.
 *
 * <p>TrainIntents carry the replay building tag, which EmulatedGame does not know.
 * The harness remaps each TrainIntent to the first available (complete) building of the
 * correct type in EmulatedGame before applying it. EmulatedGame's own queue logic then
 * handles capacity checks.
 *
 * <p>Supply, minerals, vespene, and mining probe count are synced from ground truth each tick.
 * These syncs are ground-truth scaffolding to isolate train-timing accuracy:
 * <ul>
 *   <li>Supply: EmulatedGame can't build Pylons (buildings not synced) → supply-capped at 15.
 *       Syncing supply allows training to proceed.</li>
 *   <li>Minerals/vespene: EmulatedGame's flat mining model accumulates too slowly to pay for
 *       training (calibrated for Quarkus scheduler cadence, not per-22-loop replay tick).
 *       Syncing minerals lets the unit-count test validate train-command timing in isolation.</li>
 *   <li>Mining probes: updated from ground truth unit count so the mineral accumulation rate
 *       tracks reality (between syncs).</li>
 * </ul>
 *
 * <p>EmulatedGame runs without terrain or enemy AI — economic layer only.
 */
public final class ReplayValidationHarness {

    /** Game loops per tick — 22 at SC2 Faster speed. Mirrors Sc2ReplayShared.LOOPS_PER_TICK. */
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

        // miningProbes stays at INITIAL_PROBES — synced from ground truth after each tick.

        assertInitialStateMatch(replayGame.snapshot(), emulated.snapshot(), replayPath, playerId);

        ReplayCommandStream commands = ReplayCommandExtractor.extract(replayPath, playerId);
        List<TimedIntent>   intents  = commands.intents();
        int                 cursor   = 0;

        List<DivergenceReport.TickSnapshot> snapshots = new ArrayList<>(Math.min(tickLimit, 600));

        // EmulatedGame already has correct initial supply (15) and miningProbes (12) from reset().
        // After each tick, sync from ground truth for the next tick.

        for (int tick = 0; tick < tickLimit && !replayGame.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * LOOPS_PER_TICK;

            emulated.tick();
            replayGame.tick();

            // Apply all TrainIntents whose loop falls within this tick's window (after ticking).
            // Applying post-tick aligns completion timing: the intent sees the post-tick gameFrame,
            // so a probe ordered at loop=1 (tick 0 window) completes at gameFrame(1)+12=13, firing
            // inside tick 12 — which matches the replay's ground truth probe completion at tick ~12.
            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                TimedIntent ti = intents.get(cursor++);
                if (ti.intent() instanceof TrainIntent train) {
                    TrainIntent remapped = remapToEmulatedBuilding(train, emulated.snapshot());
                    if (remapped != null) {
                        emulated.applyIntent(remapped);
                    }
                } else {
                    emulated.applyIntent(ti.intent());
                }
            }

            GameState gt = replayGame.snapshot();
            GameState em = emulated.snapshot();

            snapshots.add(new DivergenceReport.TickSnapshot(
                tick,
                gt.myUnits().size(),     em.myUnits().size(),
                gt.myBuildings().size(), em.myBuildings().size(),
                gt.minerals(),           em.minerals(),
                gt.vespene(),            em.vespene()));

            // Sync resources from ground truth for the next tick (scaffolding to isolate train timing).
            // Supply: real player builds Pylons we can't reconstruct — sync prevents supply cap.
            // Minerals/vespene: EmulatedGame's flat mining model is calibrated for Quarkus scheduler
            // cadence, not per-22-loop replay ticks — sync lets the unit-count test validate
            // train-command timing without being blocked by resource shortfalls.
            // Mining probes: synced so the accumulation rate (between syncs) tracks reality.
            if (gt.supply() > 0) {
                emulated.setSupplyCap(gt.supply());
            }
            if (gt.minerals() > 0 || em.minerals() != gt.minerals()) {
                emulated.setMinerals(gt.minerals());
            }
            if (gt.vespene() > 0 || em.vespene() != gt.vespene()) {
                emulated.setVespene(gt.vespene());
            }
            emulated.setMiningProbes(countProbes(gt));
        }

        return DivergenceReport.from(snapshots);
    }

    /** Counts Probe units in a GameState snapshot. */
    private static int countProbes(GameState state) {
        return (int) state.myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE)
            .count();
    }

    /**
     * Remaps a TrainIntent's building tag from the replay-world tag to an EmulatedGame tag.
     * Finds the first complete building of the required type in the current emulated snapshot.
     * Returns null if no suitable building exists (intent is dropped — emulated hasn't built it yet).
     */
    private static TrainIntent remapToEmulatedBuilding(TrainIntent train, GameState emState) {
        BuildingType required = SC2Data.trainedBy(train.unitType());
        return emState.myBuildings().stream()
            .filter(b -> b.isComplete() && (required == BuildingType.UNKNOWN || b.type() == required))
            .findFirst()
            .map(b -> new TrainIntent(b.tag(), train.unitType()))
            .orElse(null);
    }

    /**
     * Checks structural initial state (unit and building counts only).
     * Minerals are intentionally excluded: ReplaySimulatedGame.reset() processes loop-0
     * tracker events, but the first PlayerStats event arrives at loop 22 (not 0), so
     * the replay starts with 0 minerals while EmulatedGame seeds 50. This resolves itself
     * after the first tick when the first PlayerStats event fires.
     */
    private static void assertInitialStateMatch(GameState gt, GameState em,
                                                 Path replayPath, int playerId) {
        if (gt.myUnits().size() != em.myUnits().size()
                || gt.myBuildings().size() != em.myBuildings().size()) {
            throw new IllegalStateException(String.format(
                "Initial structural mismatch for %s player %d — " +
                "replay: %d units, %d buildings; " +
                "emulated: %d units, %d buildings",
                replayPath.getFileName(), playerId,
                gt.myUnits().size(), gt.myBuildings().size(),
                em.myUnits().size(), em.myBuildings().size()));
        }
    }
}
