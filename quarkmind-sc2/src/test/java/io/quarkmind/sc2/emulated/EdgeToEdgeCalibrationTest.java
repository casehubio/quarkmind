package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

@Tag("diagnostic")
class EdgeToEdgeCalibrationTest {

    @AfterEach
    void resetDefaults() {
        SimulatedWorker.resetMiningDuration();
        SimulatedWorker.resetDepositDuration();
        WorkerSimulation.useEdgeToEdge = false;
    }

    @Test
    void sweepMiningDurationAtEdgeDistances() {
        CalibrationBaseline sc2    = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        int                 target = sc2.snapshots().get(304).minerals();

        System.out.println("=== Mining Duration Fine Sweep (edge-to-edge + collision, factor=1.0) ===");
        System.out.printf("SC2 target at tick 304: %d minerals%n%n", target);
        System.out.printf("%-8s | %8s | %8s | %8s | %10s%n",
                          "mining", "EM t304", "delta", "mismatch", "max gap");
        System.out.println("---------|----------|----------|----------|----------");

        for (double mining = 13.0; mining <= 16.0; mining += 0.5) {
            SimulatedWorker.setMiningDurationLoops(mining);
            WorkerSimulation.useEdgeToEdge = true;

            EmulatedGame game = setupGame(sc2);

            int maxGap        = 0;
            int mismatchCount = 0;
            int prevSc2       = sc2.snapshots().get(0).minerals();
            int prevEm        = game.snapshot().minerals();

            for (int tick = 1; tick <= 304; tick++) {
                game.tick();
                int sc2Min   = sc2.snapshots().get(tick).minerals();
                int emMin    = game.snapshot().minerals();
                int sc2Delta = sc2Min - prevSc2;
                int emDelta  = emMin - prevEm;
                int gap      = Math.abs(emMin - sc2Min);
                if (gap > maxGap) {maxGap = gap;}
                if (sc2Delta != emDelta) {mismatchCount++;}
                prevSc2 = sc2Min;
                prevEm  = emMin;
            }

            int emFinal = game.snapshot().minerals();
            int delta   = emFinal - target;
            System.out.printf("%7.1f  | %8d | %+8d | %4d/304 | %10d%n",
                              mining, emFinal, delta, mismatchCount, maxGap);
        }
    }

    private static EmulatedGame setupGame(CalibrationBaseline cal) {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (cal.basePosition() != null && cal.mineralPatches() != null) {
            float fx = 8f - cal.basePosition()[0];
            float fy = 8f - cal.basePosition()[1];
            game.setMineralPatchResources(cal.mineralPatches().stream()
                    .map(p -> new Resource(
                            "cal-" + cal.mineralPatches().indexOf(p),
                            new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                    .toList());
        }
        game.reset();
        return game;
    }

    private static CalibrationBaseline loadBaseline(String resource) {
        try {
            return CalibrationBaseline.fromJson(
                    Files.readString(Path.of("src/test/resources/" + resource)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
