package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.GameResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Connects to real SC2, starts a game, and measures the mining animation
 * duration by tracking a single worker's position frame-by-frame.
 *
 * Stationary periods at mineral patch positions = mining animation.
 * Moving periods = travel time.
 *
 * Refs #583
 */
class MiningAnimationMeasurementTest {

    private static final double POSITION_THRESHOLD = 0.1;
    private static final int MEASUREMENT_LOOPS = 3000;

    @Test
    void measureMiningAnimationDuration() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
            "StarCraft II not installed — skipping");

        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port = 8168;
        transport.mapName = "TorchesAIE_v4";
        transport.difficultyStr = "VERY_EASY";
        transport.aiRaceStr = "RANDOM";
        transport.botRaceStr = "PROTOSS";
        transport.connectRetryCount = 60;
        transport.connectRetryIntervalMs = 5000;
        transport.connect();
        transport.createGame();
        transport.joinGame();

        MiningTracker tracker = new MiningTracker(transport);
        transport.runGameLoop(tracker);

        Thread.sleep(1000);
        while (transport.isRunning()) {
            Thread.sleep(100);
        }

        tracker.printResults();
    }

    @org.junit.jupiter.api.Tag("sc2")
    @Test
    void measureVolumeBasedTransfer() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping");

        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port                = 8168;
        transport.mapName                = "TorchesAIE_v4";
        transport.difficultyStr          = "VERY_EASY";
        transport.aiRaceStr              = "RANDOM";
        transport.botRaceStr             = "PROTOSS";
        transport.connectRetryCount      = 60;
        transport.connectRetryIntervalMs = 5000;
        transport.connect();
        transport.createGame();
        transport.joinGame();

        DepletionTracker tracker = new DepletionTracker(transport);
        transport.runGameLoop(tracker);

        Thread.sleep(1000);
        while (transport.isRunning()) {
            Thread.sleep(100);
        }

        tracker.printResults();
    }


    static class MiningTracker implements SC2FrameCallback {
        private final QuarkusSC2Transport transport;
        private int tick = 0;
        private Point2d lastPosition;

        record PositionSample(int loop, Point2d position, double distFromLast) {}
        record StationaryPeriod(int startLoop, int endLoop, int duration, Point2d position) {}

        private final List<PositionSample> samples = new ArrayList<>();
        private final List<StationaryPeriod> stationaryPeriods = new ArrayList<>();
        private int stationaryStart = -1;
        private Point2d stationaryPos;

        MiningTracker(QuarkusSC2Transport transport) {
            this.transport = transport;
        }

        @Override
        public void onGameStart(ResponseGameInfo info) {
            System.out.println("[Mining] Game started — tracking worker positions");
        }

        @Override
        public void onStep(Observation obs) throws InterruptedException {
            if (tick >= MEASUREMENT_LOOPS) {
                transport.quit();
                return;
            }

            GameState state = ObservationTranslator.translate(obs, null);
            Unit trackedWorker = null;
            for (Unit u : state.myUnits()) {
                if (u.type() == UnitType.PROBE) {
                    trackedWorker = u;
                    break;
                }
            }

            if (trackedWorker != null) {
                Point2d pos = trackedWorker.position();
                double dist = lastPosition != null ? pos.distanceTo(lastPosition) : 0;
                samples.add(new PositionSample(tick, pos, dist));

                if (dist < POSITION_THRESHOLD) {
                    if (stationaryStart < 0) {
                        stationaryStart = tick;
                        stationaryPos = pos;
                    }
                } else {
                    if (stationaryStart >= 0) {
                        int duration = tick - stationaryStart;
                        if (duration >= 2) {
                            stationaryPeriods.add(new StationaryPeriod(
                                stationaryStart, tick, duration, stationaryPos));
                        }
                        stationaryStart = -1;
                    }
                }
                lastPosition = pos;
            }
            tick++;
        }

        @Override
        public void onGameEnd(GameResult result) {
            if (stationaryStart >= 0) {
                int duration = tick - stationaryStart;
                stationaryPeriods.add(new StationaryPeriod(
                    stationaryStart, tick, duration, stationaryPos));
            }
            System.out.printf("[Mining] Game ended after %d loops, result=%s%n", tick, result);
        }

        void printResults() {
            System.out.printf("%n=== Mining Animation Measurement ===%n");
            System.out.printf("Total samples: %d%n", samples.size());
            System.out.printf("Stationary periods (>= 2 loops): %d%n%n", stationaryPeriods.size());

            System.out.printf("%-8s %-8s %-8s %-8s %-20s%n",
                "Start", "End", "Loops", "Seconds", "Position");
            System.out.println("-".repeat(60));

            List<Integer> miningDurations = new ArrayList<>();
            List<Integer> depositDurations = new ArrayList<>();

            for (StationaryPeriod sp : stationaryPeriods) {
                double seconds = sp.duration / SC2Data.GAME_LOOPS_PER_SECOND;
                System.out.printf("%-8d %-8d %-8d %-8.3f (%.1f, %.1f)%n",
                    sp.startLoop, sp.endLoop, sp.duration, seconds,
                    sp.position.x(), sp.position.y());

                if (sp.duration >= 3 && sp.duration <= 100) {
                    miningDurations.add(sp.duration);
                }
            }

            System.out.println();

            if (!miningDurations.isEmpty()) {
                double avg = miningDurations.stream().mapToInt(i -> i).average().orElse(0);
                int min = miningDurations.stream().mapToInt(i -> i).min().orElse(0);
                int max = miningDurations.stream().mapToInt(i -> i).max().orElse(0);
                double median = miningDurations.stream().sorted()
                    .mapToInt(i -> i).toArray()[miningDurations.size() / 2];

                System.out.printf("Mining animation statistics (3-100 loop range):%n");
                System.out.printf("  Count:  %d%n", miningDurations.size());
                System.out.printf("  Mean:   %.1f loops (%.3f seconds)%n",
                    avg, avg / SC2Data.GAME_LOOPS_PER_SECOND);
                System.out.printf("  Median: %.0f loops (%.3f seconds)%n",
                    median, median / SC2Data.GAME_LOOPS_PER_SECOND);
                System.out.printf("  Min:    %d loops (%.3f seconds)%n",
                    min, (double) min / SC2Data.GAME_LOOPS_PER_SECOND);
                System.out.printf("  Max:    %d loops (%.3f seconds)%n",
                    max, (double) max / SC2Data.GAME_LOOPS_PER_SECOND);

                System.out.printf("%n  Current MINING_DURATION_LOOPS: 12.0 (0.536s)%n");
                System.out.printf("  Measured vs current: %.1f loops difference%n",
                    avg - 12.0);
            }

            if (samples.size() > 10) {
                System.out.println("\n=== First 10 movement samples ===");
                int shown = 0;
                for (PositionSample s : samples) {
                    if (s.distFromLast > POSITION_THRESHOLD) {
                        System.out.printf("  Loop %d: moved %.3f to (%.1f, %.1f)%n",
                            s.loop, s.distFromLast, s.position.x(), s.position.y());
                        if (++shown >= 10) break;
                    }
                }
            }
        }
    }

    static class DepletionTracker implements SC2FrameCallback {
        private static final int                                                           DURATION_LOOPS = 20000; // ~900 ticks, enough for depletion
        private static final java.util.Set<com.github.ocraft.s2client.protocol.data.Units> MINERAL_TYPES  = java.util.Set.of(
                com.github.ocraft.s2client.protocol.data.Units.NEUTRAL_MINERAL_FIELD,
                com.github.ocraft.s2client.protocol.data.Units.NEUTRAL_MINERAL_FIELD750,
                com.github.ocraft.s2client.protocol.data.Units.NEUTRAL_MINERAL_FIELD450,
                com.github.ocraft.s2client.protocol.data.Units.NEUTRAL_RICH_MINERAL_FIELD,
                com.github.ocraft.s2client.protocol.data.Units.NEUTRAL_RICH_MINERAL_FIELD750
                                                                                                                            );

        private final QuarkusSC2Transport transport;
        private       int                 loop = 0;
        private       int                 tick = -1;

        record PatchState(long tag, int remaining) {}

        record TickSnapshot(int tick, int playerMinerals, List<PatchState> patches,
                            List<io.quarkmind.sc2.playbook.WorkerSnapshot> workers) {}

        private final List<TickSnapshot> snapshots = new ArrayList<>();
        private       float              baseX, baseY;

        DepletionTracker(QuarkusSC2Transport transport) {
            this.transport = transport;
        }

        @Override
        public void onGameStart(ResponseGameInfo info) {
            System.out.printf("[Depletion] Game started — running %d loops (~%d ticks)%n",
                              DURATION_LOOPS, DURATION_LOOPS / SC2Data.LOOPS_PER_TICK);
        }

        @Override
        public void onStep(Observation obs) throws InterruptedException {
            if (loop >= DURATION_LOOPS) {
                transport.quit();
                return;
            }

            int emTick = loop / SC2Data.LOOPS_PER_TICK;
            if (emTick > tick) {
                tick = emTick;
                var rawUnits = obs.getRaw().orElseThrow().getUnits();

                if (tick % 5 == 0) {
                    var enemyTags = rawUnits.stream()
                        .filter(u -> u.getAlliance() == com.github.ocraft.s2client.protocol.unit.Alliance.ENEMY)
                        .map(u -> u.getTag())
                        .toList();
                    if (!enemyTags.isEmpty()) {
                        var debugReq = SC2APIProtocol.Debug.DebugKillUnit.newBuilder();
                        enemyTags.forEach(t -> debugReq.addTag(t.getValue()));
                        transport.sendDebug(SC2APIProtocol.Sc2Api.RequestDebug.newBuilder()
                            .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder().setKillUnit(debugReq.build()).build())
                            .build());
                    }
                }

                int playerMinerals = obs.getPlayerCommon().getMinerals();

                List<PatchState> patches = new ArrayList<>();
                for (var u : rawUnits) {
                    if (u.getAlliance() == com.github.ocraft.s2client.protocol.unit.Alliance.NEUTRAL
                        && MINERAL_TYPES.contains(u.getType())) {
                        var pos = u.getPosition();
                        double distToBase = Math.sqrt(
                                (pos.getX() - baseX) * (pos.getX() - baseX) +
                                (pos.getY() - baseY) * (pos.getY() - baseY));
                        if (distToBase < 12.0) {
                            patches.add(new PatchState(
                                    u.getTag().getValue(),
                                    u.getMineralContents().orElse(-1)));
                        }
                    }
                }

                var workers = ObservationTranslator.extractWorkerCadence(tick, rawUnits).workers();

                if (tick == 0) {
                    GameState state = ObservationTranslator.translate(obs, null);
                    state.myBuildings().stream().filter(b -> b.isComplete()).findFirst()
                         .ifPresent(b -> {
                             baseX = b.position().x();
                             baseY = b.position().y();
                         });
                }

                snapshots.add(new TickSnapshot(tick, playerMinerals, patches, workers));
            }
            loop++;
        }

        @Override
        public void onGameEnd(GameResult result) {
            System.out.printf("[Depletion] Game ended after %d loops (%d ticks), result=%s%n",
                              loop, tick, result);
        }

        void printResults() {
            System.out.printf("%n=== Mineral Patch Depletion Analysis ===%n");
            System.out.printf("Base position: (%.1f, %.1f)%n", baseX, baseY);
            System.out.printf("Total ticks recorded: %d%n%n", snapshots.size());

            // Track per-patch depletion
            java.util.Map<Long, Integer> prevPatchAmounts     = new java.util.HashMap<>();
            java.util.Map<Long, Integer> prevPrevPatchAmounts = new java.util.HashMap<>();
            int                          prevPlayerMinerals   = 0;

            // Track worker carry transitions for cycle timing
            java.util.Map<String, Boolean> prevCarrying    = new java.util.HashMap<>();
            java.util.Map<String, Integer> lastDepositTick = new java.util.HashMap<>();
            java.util.Map<String, Integer> lastPickupTick  = new java.util.HashMap<>();

            List<Integer> normalCycleDurations    = new ArrayList<>();
            List<Integer> depletionCycleDurations = new ArrayList<>();
            List<String>  depletionEvents         = new ArrayList<>();

            for (TickSnapshot snap : snapshots) {
                int mineralDelta = snap.playerMinerals - prevPlayerMinerals;

                // Track patch depletion
                for (PatchState ps : snap.patches) {
                    Integer prev = prevPatchAmounts.get(ps.tag);
                    if (prev != null && prev != ps.remaining && ps.remaining >= 0) {
                        int extracted = prev - ps.remaining;
                        if (extracted > 0 && extracted != 5 && extracted != 7) {
                            depletionEvents.add(String.format(
                                    "  tick %d: patch %d: %d→%d (extracted %d — PARTIAL)",
                                    snap.tick, ps.tag, prev, ps.remaining, extracted));
                        }
                        if (ps.remaining == 0 && prev > 0) {
                            depletionEvents.add(String.format(
                                    "  tick %d: patch %d DEPLETED (was %d, extracted %d)",
                                    snap.tick, ps.tag, prev, extracted));
                        }
                    }
                    prevPatchAmounts.put(ps.tag, ps.remaining);
                }

                // Track worker cycles
                for (var w : snap.workers) {
                    Boolean wasCarrying = prevCarrying.get(w.tag());
                    if (wasCarrying != null && wasCarrying != w.carrying()) {
                        if (w.carrying() && !wasCarrying) {
                            // PICKUP
                            lastPickupTick.put(w.tag(), snap.tick);
                            Integer lastDep = lastDepositTick.get(w.tag());
                            if (lastDep != null) {
                                int mineCycleDuration = snap.tick - lastDep;
                                // Check if any nearby patch just depleted
                                java.util.Map<Long, Integer> prevPrev = prevPrevPatchAmounts;
                                boolean nearDepletion = snap.patches.stream()
                                                                    .anyMatch(ps -> {
                                                                        Integer p = prevPrev.get(ps.tag);
                                                                        return p != null && p <= 10;
                                                                    });
                                if (nearDepletion) {
                                    depletionCycleDurations.add(mineCycleDuration);
                                } else {
                                    normalCycleDurations.add(mineCycleDuration);
                                }
                            }
                        } else if (!w.carrying() && wasCarrying) {
                            // DEPOSIT
                            lastDepositTick.put(w.tag(), snap.tick);
                        }
                    }
                    prevCarrying.put(w.tag(), w.carrying());
                }

                // Check for non-5 mineral income
                if (mineralDelta > 0 && mineralDelta % 5 != 0) {
                    depletionEvents.add(String.format(
                            "  tick %d: player minerals +%d (NON-MULTIPLE OF 5)",
                            snap.tick, mineralDelta));
                }

                prevPrevPatchAmounts = new java.util.HashMap<>(prevPatchAmounts);
                prevPlayerMinerals   = snap.playerMinerals;
            }

            // Print results
            System.out.println("=== Patch Depletion Events ===");
            if (depletionEvents.isEmpty()) {
                System.out.println("  (no depletion events in recorded ticks)");
            } else {
                depletionEvents.forEach(System.out::println);
            }

            System.out.printf("%n=== Mining Cycle Duration Comparison ===%n");
            if (!normalCycleDurations.isEmpty()) {
                double avgNormal = normalCycleDurations.stream().mapToInt(i -> i).average().orElse(0);
                System.out.printf("Normal cycles:     n=%d, mean=%.1f ticks, min=%d, max=%d%n",
                                  normalCycleDurations.size(), avgNormal,
                                  normalCycleDurations.stream().mapToInt(i -> i).min().orElse(0),
                                  normalCycleDurations.stream().mapToInt(i -> i).max().orElse(0));
            }
            if (!depletionCycleDurations.isEmpty()) {
                double avgDepletion = depletionCycleDurations.stream().mapToInt(i -> i).average().orElse(0);
                System.out.printf("Near-depletion cycles: n=%d, mean=%.1f ticks, min=%d, max=%d%n",
                                  depletionCycleDurations.size(), avgDepletion,
                                  depletionCycleDurations.stream().mapToInt(i -> i).min().orElse(0),
                                  depletionCycleDurations.stream().mapToInt(i -> i).max().orElse(0));
            }

            // Final patch state
            if (!snapshots.isEmpty()) {
                TickSnapshot last = snapshots.getLast();
                System.out.printf("%n=== Final Patch State (tick %d) ===%n", last.tick);
                for (PatchState ps : last.patches) {
                    System.out.printf("  patch %d: %d remaining%n", ps.tag, ps.remaining);
                }
                System.out.printf("  Player minerals: %d%n", last.playerMinerals);
            }
        }
    }

}
