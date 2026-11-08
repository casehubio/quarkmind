package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
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
}
