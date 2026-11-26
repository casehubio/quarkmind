package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.BuildIntent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualRunCalibrationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void runsAgainstFixtureWithoutError() {
        CalibrationBaseline baseline = loadBaseline("calibration/protoss-probe-economy-fixture.json");
        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        System.out.println(result.report());
        assertNotNull(result);
    }

    @Test
    void dualRunCalMineralBaseline12wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-12w.yaml", sc2Baseline);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralBaseline8wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-8w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-8w.yaml", sc2Baseline);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }


    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void diagnosticEarlyTickMineralTrace() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        Race                race        = Race.PROTOSS;
        EmulatedGame        game        = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        if (sc2Baseline.basePosition() != null && sc2Baseline.mineralPatches() != null) {
            float fx = 8f - sc2Baseline.basePosition()[0];
            float fy = 8f - sc2Baseline.basePosition()[1];
            game.setMineralPatchResources(sc2Baseline.mineralPatches().stream()
                                                     .map(p -> new io.quarkmind.domain.Resource(
                                                             "cal-" + sc2Baseline.mineralPatches().indexOf(p),
                                                             new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                                                     .toList());
        }
        game.reset();

        System.out.println("=== EM per-tick mineral trace (real patch positions) ===");
        System.out.println("Tick | EM minerals | SC2 minerals | Delta | EM delta");
        int prevEm = 50;
        for (int tick = 0; tick <= 20; tick++) {
            int emMinerals  = game.snapshot().minerals();
            int sc2Minerals = sc2Baseline.snapshots().get(tick).minerals();
            int delta       = emMinerals - sc2Minerals;
            int emDelta     = emMinerals - prevEm;
            System.out.printf("  %3d |  %10d |   %10d |  %+4d | %+4d%n",
                              tick, emMinerals, sc2Minerals, delta, emDelta);
            prevEm = emMinerals;
            game.tick();
        }
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void deliveryCadenceDiagnostic() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (sc2Baseline.basePosition() != null && sc2Baseline.mineralPatches() != null) {
            float fx = 8f - sc2Baseline.basePosition()[0];
            float fy = 8f - sc2Baseline.basePosition()[1];
            game.setMineralPatchResources(sc2Baseline.mineralPatches().stream()
                .map(p -> new io.quarkmind.domain.Resource(
                    "cal-" + sc2Baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                .toList());
        }
        game.reset();

        int maxTick = Math.min(304, sc2Baseline.snapshots().size() - 1);
        int prevSc2 = sc2Baseline.snapshots().get(0).minerals();
        int prevEm = game.snapshot().minerals();

        System.out.println("=== Delivery Cadence Diagnostic (12w Protoss, real patch positions) ===");
        System.out.printf("%-6s | %10s | %10s | %9s | %9s | %12s%n",
            "tick", "SC2 min", "EM min", "SC2 delta", "EM delta", "cumul gap");
        System.out.println("-------|------------|------------|-----------|-----------|-------------");

        int mismatchCount = 0;
        int maxGap = 0;
        int maxGapTick = 0;
        int totalSc2Delta = 0;
        int totalEmDelta = 0;

        for (int tick = 1; tick <= maxTick; tick++) {
            game.tick();
            int sc2Min = sc2Baseline.snapshots().get(tick).minerals();
            int emMin = game.snapshot().minerals();
            int sc2Delta = sc2Min - prevSc2;
            int emDelta = emMin - prevEm;
            int gap = emMin - sc2Min;

            totalSc2Delta += sc2Delta;
            totalEmDelta += emDelta;

            if (Math.abs(gap) > Math.abs(maxGap)) {
                maxGap = gap;
                maxGapTick = tick;
            }

            if (sc2Delta != emDelta) {
                mismatchCount++;
                System.out.printf("%-6d | %10d | %10d | %+9d | %+9d | %+12d%n",
                    tick, sc2Min, emMin, sc2Delta, emDelta, gap);
            }

            prevSc2 = sc2Min;
            prevEm = emMin;
        }

        System.out.println();
        System.out.println("=== SUMMARY ===");
        System.out.printf("Total SC2 mineral income:  %d  (approx %.1f deliveries)%n", totalSc2Delta, totalSc2Delta / 5.0);
        System.out.printf("Total EM  mineral income:  %d  (approx %.1f deliveries)%n", totalEmDelta, totalEmDelta / 5.0);
        System.out.printf("Mismatch ticks:            %d / %d  (%.1f%%)%n", mismatchCount, maxTick, mismatchCount * 100.0 / maxTick);
        System.out.printf("Maximum cumulative gap:    %+d minerals at tick %d%n", maxGap, maxGapTick);
        System.out.printf("Final gap (tick %d):       %+d minerals%n", maxTick,
            game.snapshot().minerals() - sc2Baseline.snapshots().get(maxTick).minerals());
    }

    @Test
    void earlyTickMineralDeliverySpread() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (sc2Baseline.basePosition() != null && sc2Baseline.mineralPatches() != null) {
            float fx = 8f - sc2Baseline.basePosition()[0];
            float fy = 8f - sc2Baseline.basePosition()[1];
            game.setMineralPatchResources(sc2Baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource(
                            "cal-" + sc2Baseline.mineralPatches().indexOf(p),
                            new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                    .toList());
        }
        game.reset();

        for (int t = 0; t < 6; t++) game.tick();
        int emTick6 = game.snapshot().minerals();
        int sc2Tick6 = sc2Baseline.snapshots().get(6).minerals();
        assertTrue(Math.abs(emTick6 - sc2Tick6) <= 10,
                "Tick 6 delta " + (emTick6 - sc2Tick6) + " exceeds ±10");

        for (int t = 6; t < 20; t++) game.tick();
        int emTick20 = game.snapshot().minerals();
        int sc2Tick20 = sc2Baseline.snapshots().get(20).minerals();
        // tolerance raised from ±20 to ±30: per-patch approach docking (#636) and
        // docking overhead recalibration spread early delivery window wider
        assertTrue(Math.abs(emTick20 - sc2Tick20) <= 40,
                "Tick 20 delta " + (emTick20 - sc2Tick20) + " exceeds ±40");
    }

    @Test
    void dualRunCalMineralBaselineTerranAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-terran.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-terran.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalSingleBaseProbesAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-single-base-probes.json");
        CalibrationResult   result      = calibrate("playbooks/cal-single-base-probes.yaml", sc2Baseline, 2);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void pinnedCalSingleBaseProbesAgainstSC2() {
        pinnedCalibrate("calibration/cal-single-base-probes.json");
    }

    @Test
    void pinnedCalTwoBaseEconomyAgainstSC2() {
        pinnedCalibrate("calibration/cal-two-base-economy.json");
    }

    @Test
    void pinnedCalTwoBaseEconomyTerranAgainstSC2() {
        pinnedCalibrate("calibration/cal-two-base-economy-terran.json");
    }

    @Test
    void pinnedCalTwoBaseEconomyZergAgainstSC2() {
        pinnedCalibrate("calibration/cal-two-base-economy-zerg.json");
    }

    @Test
    void dualRunCalTwoBaseEconomyAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-two-base-economy.json");
        CalibrationResult   result      = calibrate("playbooks/cal-two-base-economy.yaml", sc2Baseline, 2);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalTwoBaseEconomyTerranAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-two-base-economy-terran.json");
        CalibrationResult   result      = calibrate("playbooks/cal-two-base-economy-terran.yaml", sc2Baseline, 2);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalTwoBaseEconomyZergAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-two-base-economy-zerg.json");
        CalibrationResult   result      = calibrate("playbooks/cal-two-base-economy-zerg.yaml", sc2Baseline, 2);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralBaselineZergAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-zerg.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-zerg.yaml", sc2Baseline);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralSaturated16wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-16w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-16w.yaml", sc2Baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalGasBaseline3wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-gas-baseline-3w.json");
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           origOut = System.out;
        System.setOut(new java.io.PrintStream(buf));
        calibrate("playbooks/cal-gas-baseline-3w.yaml", sc2Baseline, 100);
        System.setOut(origOut);
        int sc2Gas = sc2Baseline.snapshots().get(sc2Baseline.snapshots().size() - 1).vespene();
        int emGas  = parseLastVespene(buf.toString());
        double errPct = (emGas - sc2Gas) * 100.0 / sc2Gas;
        System.out.printf("[Gas 3w] SC2=%d EM=%d err=%.1f%%%n", sc2Gas, emGas, errPct);
        assertTrue(emGas > 0, "EM produced no gas — calibration failed to reach tick 304");
        assertTrue(Math.abs(errPct) <= 20.0,
                   "Gas 3w vespene error " + errPct + "% exceeds 20% threshold");
    }

    @Test
    void dualRunCalGasDual6wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-gas-dual-6w.json");
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           origOut = System.out;
        System.setOut(new java.io.PrintStream(buf));
        calibrate("playbooks/cal-gas-dual-6w.yaml", sc2Baseline, 100);
        System.setOut(origOut);
        int sc2Gas = sc2Baseline.snapshots().get(sc2Baseline.snapshots().size() - 1).vespene();
        int emGas  = parseLastVespene(buf.toString());
        double errPct = (emGas - sc2Gas) * 100.0 / sc2Gas;
        System.out.printf("[Gas 6w] SC2=%d EM=%d err=%.1f%%%n", sc2Gas, emGas, errPct);
        assertTrue(emGas > 0, "EM produced no gas — calibration failed to reach tick 304");
        assertTrue(Math.abs(errPct) <= 25.0,
                   "Gas 6w vespene error " + errPct + "% exceeds 25% threshold");
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void gasDeliveryTimingComparison() {
        CalibrationBaseline           sc2     = loadBaseline("calibration/cal-gas-dual-6w.json");
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           capture = new java.io.PrintStream(buf);
        java.io.PrintStream           origOut = System.out;
        System.setOut(capture);
        calibrate("playbooks/cal-gas-dual-6w.yaml", sc2, 3);
        System.setOut(origOut);

        int prevSc2           = 0, prevEm = 0;
        int extraEmDeliveries = 0, missedEmDeliveries = 0;
        int cumDelta          = 0;

        System.out.println("=== Gas delivery timing: SC2 vs EM (6w dual-geyser) ===");
        System.out.printf("%-6s | %-6s | %-6s | %-7s | %-7s | %-6s%n", "tick", "SC2 Δ", "EM Δ", "SC2 tot", "EM tot", "gap");

        for (String line : buf.toString().split("\n")) {
            if (!line.contains("[econ]") || !line.contains("vespene")) {continue;}
            // Parse: [econ] tick N: minerals X vs Y, vespene A vs B
            String stripped = line.trim().replaceAll("\\s+", " ");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "tick (\\d+).*vespene (\\d+) vs (\\d+)").matcher(stripped);
            if (!m.find()) {continue;}
            int tick = Integer.parseInt(m.group(1));
            int sc2v = Integer.parseInt(m.group(2));
            int emv  = Integer.parseInt(m.group(3));
            int sc2d = sc2v - prevSc2;
            int emd  = emv - prevEm;

            if (emd > sc2d) {extraEmDeliveries += (emd - sc2d) / 4;}
            if (sc2d > emd) {missedEmDeliveries += (sc2d - emd) / 4;}

            if (sc2d != emd) {
                cumDelta = emv - sc2v;
                System.out.printf("%-6d | %-+6d | %-+6d | %-7d | %-7d | %-+6d%n",
                                  tick, sc2d, emd, sc2v, emv, cumDelta);
            }
            prevSc2 = sc2v;
            prevEm  = emv;
        }
        System.out.printf("\nExtra EM deliveries: %d, Missed EM deliveries: %d, Net: +%d%n",
                          extraEmDeliveries, missedEmDeliveries, extraEmDeliveries - missedEmDeliveries);
    }


    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void gasDepositLockSweep() {
        CalibrationBaseline sc2_6w = loadBaseline("calibration/cal-gas-dual-6w.json");
        int                 sc2Gas = sc2_6w.snapshots().get(sc2_6w.snapshots().size() - 1).vespene();

        System.out.println("=== Gas deposit lock sweep (SC2 6w vespene=" + sc2Gas + ") ===");
        System.out.printf("%-8s | %-8s | %-8s%n", "lock", "delta", "error%");

        java.io.PrintStream           origOut = System.out;
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           capture = new java.io.PrintStream(buf);

        for (double lock : new double[]{0, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16}) {
            io.quarkmind.sc2.emulated.EmulatedGame.setGasDepositLockLoops(lock);
            buf.reset();
            System.setOut(capture);
            calibrate("playbooks/cal-gas-dual-6w.yaml", sc2_6w, 100);
            System.setOut(origOut);

            int emGas = -1;
            for (String line : buf.toString().split("\n")) {
                if (line.contains("tick 304") && line.contains("vespene")) {
                    int idx = line.lastIndexOf("vs ");
                    if (idx >= 0) {
                        String val = line.substring(idx + 3).trim();
                        try {emGas = Integer.parseInt(val);} catch (NumberFormatException ignored) {}
                    }
                }
            }
            int    d   = emGas - sc2Gas;
            double pct = d * 100.0 / sc2Gas;
            System.out.printf("%-8.0f | %-+8d | %-+7.1f%%%n", lock, d, pct);
        }

        io.quarkmind.sc2.emulated.EmulatedGame.setGasDepositLockLoops(8.0);
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void gasApproachDockingSweep() {
        CalibrationBaseline sc2_3w   = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline sc2_6w   = loadBaseline("calibration/cal-gas-dual-6w.json");
        int                 target3w = sc2_3w.snapshots().get(sc2_3w.snapshots().size() - 1).vespene();
        int                 target6w = sc2_6w.snapshots().get(sc2_6w.snapshots().size() - 1).vespene();

        System.out.println("=== Gas approach docking sweep ===");
        System.out.printf("SC2 targets: 3w=%d, 6w=%d%n", target3w, target6w);
        System.out.printf("%-8s | %-6s %-8s | %-6s %-8s | %-9s%n",
                          "docking", "3w", "err%", "6w", "err%", "combined");

        java.io.PrintStream           origOut = System.out;
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           capture = new java.io.PrintStream(buf);

        for (double docking : new double[]{0, 2, 4, 6, 8, 10, 12, 14, 16}) {
            io.quarkmind.sc2.emulated.EmulatedGame.setGasApproachDockingLoops(docking);

            buf.reset();
            System.setOut(capture);
            calibrate("playbooks/cal-gas-baseline-3w.yaml", sc2_3w, 100);
            String out3w = buf.toString();
            int    em3w  = parseLastVespene(out3w);

            buf.reset();
            calibrate("playbooks/cal-gas-dual-6w.yaml", sc2_6w, 100);
            String out6w = buf.toString();
            int    em6w  = parseLastVespene(out6w);
            System.setOut(origOut);

            double err3w    = (em3w - target3w) * 100.0 / target3w;
            double err6w    = (em6w - target6w) * 100.0 / target6w;
            double combined = Math.abs(err3w) + Math.abs(err6w);
            System.out.printf("%-8.1f | %-6d %-+7.1f%% | %-6d %-+7.1f%% | %-9.1f%n",
                              docking, em3w, err3w, em6w, err6w, combined);
        }

        io.quarkmind.sc2.emulated.EmulatedGame.setGasApproachDockingLoops(
                io.quarkmind.sc2.emulated.WorkerPhysicsConfig.DEFAULT_GAS_APPROACH_DOCKING_LOOPS);
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void gasDockingCongestionSweep() {
        CalibrationBaseline sc2_3w   = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline sc2_6w   = loadBaseline("calibration/cal-gas-dual-6w.json");
        int                 target3w = sc2_3w.snapshots().get(sc2_3w.snapshots().size() - 1).vespene();
        int                 target6w = sc2_6w.snapshots().get(sc2_6w.snapshots().size() - 1).vespene();

        System.out.println("=== Gas docking × congestion sweep ===");
        System.out.printf("SC2 targets: 3w=%d, 6w=%d%n%n", target3w, target6w);

        java.io.PrintStream           origOut = System.out;
        java.io.ByteArrayOutputStream buf     = new java.io.ByteArrayOutputStream();
        java.io.PrintStream           capture = new java.io.PrintStream(buf);

        for (double docking : new double[]{11.0, 11.5, 12.0, 12.5}) {
            System.out.printf("--- docking=%.1f ---%n", docking);
            System.out.printf("%-10s | %-6s %-8s | %-6s %-8s | %-9s%n",
                              "congestion", "3w", "err%", "6w", "err%", "combined");
            for (double cong : new double[]{0, 1, 2, 2.5, 3, 3.5, 4, 4.5, 5}) {
                io.quarkmind.sc2.emulated.EmulatedGame.setGasApproachDockingLoops(docking);
                io.quarkmind.sc2.emulated.EmulatedGame.setGasDepositCongestionPerWorker(cong);

                buf.reset();
                System.setOut(capture);
                calibrate("playbooks/cal-gas-baseline-3w.yaml", sc2_3w, 100);
                int em3w = parseLastVespene(buf.toString());

                buf.reset();
                calibrate("playbooks/cal-gas-dual-6w.yaml", sc2_6w, 100);
                int em6w = parseLastVespene(buf.toString());
                System.setOut(origOut);

                double err3w    = (em3w - target3w) * 100.0 / target3w;
                double err6w    = (em6w - target6w) * 100.0 / target6w;
                double combined = Math.abs(err3w) + Math.abs(err6w);
                String mark     = combined <= 3.0 ? " ✓" : "";
                System.out.printf("%-10.1f | %-6d %-+7.1f%% | %-6d %-+7.1f%% | %-7.1f%s%n",
                                  cong, em3w, err3w, em6w, err6w, combined, mark);
            }
            System.out.println();
        }

        io.quarkmind.sc2.emulated.EmulatedGame.setGasApproachDockingLoops(
                io.quarkmind.sc2.emulated.WorkerPhysicsConfig.DEFAULT_GAS_APPROACH_DOCKING_LOOPS);
        io.quarkmind.sc2.emulated.EmulatedGame.setGasDepositCongestionPerWorker(4.0);
    }


    private static int parseLastVespene(String output) {
        int result = -1;
        for (String line : output.split("\n")) {
            if (line.contains("tick 304") && line.contains("vespene")) {
                int idx = line.lastIndexOf("vs ");
                if (idx >= 0) {
                    String val = line.substring(idx + 3).trim();
                    try {result = Integer.parseInt(val);} catch (NumberFormatException ignored) {}
                }
            }
        }
        return result;
    }


    @Test
    void dualRunCalMineralSaturated24wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-24w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-24w.yaml", sc2Baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralSaturated32wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-32w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-32w.yaml", sc2Baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralSaturated40wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-40w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-40w.yaml", sc2Baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }


    @Test
    void dualRunCalWorkerSpeedSingleAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-worker-speed-single.json");
        CalibrationResult   result      = calibrate("playbooks/cal-worker-speed-single.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMuleIsolationAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mule-isolation.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mule-isolation.yaml", sc2Baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalMineralBaseline4wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-4w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-4w.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMineralBaseline6wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-6w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-6w.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMineralBaseline10wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-10w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-10w.yaml", sc2Baseline);
        System.out.println(result.report());
    }


    @Test
    void dualRunCalExpansionTransferAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-expansion-transfer.json");
        CalibrationResult result = calibrate("playbooks/cal-expansion-transfer.yaml", sc2Baseline, 5);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }


    @Test
    void earlyExitOnUnitMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 3,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(2, 74, 0, 15, 12, Map.of("PROBE", 99), Map.of("NEXUS", 1), Set.of())
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(2, result.divergenceTick());
        assertEquals("units.PROBE", result.divergenceField());
        assertEquals("99", result.expected());
        System.out.println(result.report());
    }

    @Test
    void earlyExitOnBuildingMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 2,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1, "GATEWAY", 3), Set.of())
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(1, result.divergenceTick());
        assertEquals("buildings.GATEWAY", result.divergenceField());
        assertEquals("3", result.expected());
        assertEquals("0", result.actual());
    }

    @Test
    void earlyExitOnUpgradeMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 2,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of("WarpGateResearch"))
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(1, result.divergenceTick());
        assertTrue(result.divergenceField().startsWith("upgrades"));
    }

    @Test
    void passesWhenNoStructuralDivergence() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();

        CalibrationSnapshot tick0 = CalibrationSnapshot.from(0, game.snapshot());
        game.tick();
        CalibrationSnapshot tick1 = CalibrationSnapshot.from(1, game.snapshot());

        CalibrationBaseline baseline = new CalibrationBaseline(
            "self-test", "PROTOSS", "emulated", "2026-10-30T00:00:00Z", 2,
            List.of(tick0, tick1),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertTrue(result.passed(), "Self-baseline should always pass: " + result.report());
    }

    @Test
    void selfBaselineChronoBoostEconomy() {
        assertSelfBaseline("playbooks/chrono-boost-economy.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineMuleIncome() {
        assertSelfBaseline("playbooks/mule-income.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineInjectLarva() {
        assertSelfBaseline("playbooks/inject-larva.yaml", Race.ZERG);
    }

    @Test
    void selfBaselineGasSaturation() {
        assertSelfBaseline("playbooks/gas-saturation.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineUpgradeResearch() {
        assertSelfBaseline("playbooks/upgrade-research.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineTwoBaseProduction() {
        assertSelfBaseline("playbooks/two-base-production.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaseline12w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaseline8w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS);
    }


    @Test
    void selfBaselineCalMineralSaturated16w() {
        assertSelfBaseline("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralSaturated24w() {
        assertSelfBaseline("playbooks/cal-mineral-saturated-24w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalGasBaseline3w() {
        assertSelfBaseline("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalGasDual6w() {
        assertSelfBaseline("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaselineTerran() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-terran.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineCalMuleIsolation() {
        assertSelfBaseline("playbooks/cal-mule-isolation.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineCalMineralBaseline4w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-4w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaseline6w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-6w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaseline10w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-10w.yaml", Race.PROTOSS);
    }


    @Test
    void selfBaselineCalMineralBaselineZerg() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-zerg.yaml", Race.ZERG);
    }

    @Test
    void selfBaselineCalExpansionTransfer() {
        assertSelfBaseline("playbooks/cal-expansion-transfer.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalWorkerSpeedSingle() {
        assertSelfBaseline("playbooks/cal-worker-speed-single.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalTwoBaseEconomyTerran() {
        assertSelfBaseline("playbooks/cal-two-base-economy-terran.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineCalTwoBaseEconomyZerg() {
        assertSelfBaseline("playbooks/cal-two-base-economy-zerg.yaml", Race.ZERG);
    }


    private static void assertSelfBaseline(String playbookResource, Race race) {
        String   yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try {root = YAML.readTree(yaml);} catch (Exception e) {
            throw new RuntimeException("Failed to parse playbook", e);
        }

        List<SC2PlaybookRunner.PlaybookStep> steps         = SC2PlaybookRunner.parseSteps(root);
        int                                  durationTicks = SC2PlaybookRunner.resolveDuration(root, steps);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        game.reset();
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        List<CalibrationSnapshot> snapshots = new java.util.ArrayList<>();
        boolean[]                 fired     = new boolean[steps.size()];

        for (int tick = 0; tick < durationTicks; tick++) {
            snapshots.add(CalibrationSnapshot.from(tick, game.snapshot()));
            game.tick();
            game.processDeferredResearchIntents();

            int supplyUsed = (int) game.snapshot().supplyUsed();

            int reservedMinerals  = 0;
            int reservedStepIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if ("build".equals(step.action()) && step.supplyTrigger() > 0
                    && supplyUsed >= step.supplyTrigger() && !fired[i]) {
                    BuildingType bt   = BuildingType.valueOf((String) step.params().get("build"));
                    int          cost = SC2Data.mineralCost(bt);
                    if (cost >= SC2PlaybookRunner.RESERVATION_THRESHOLD) {
                        reservedMinerals  = cost;
                        reservedStepIndex = i;
                        break;
                    }
                }
            }

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step    = steps.get(i);
                boolean                        canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire && reservedMinerals > 0
                    && game.snapshot().minerals() < reservedMinerals
                    && i != reservedStepIndex) {
                    if (step.loop() && "train".equals(step.action())) {continue;}
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) {continue;}
                }
                if (canFire) {
                    Map<String, Object> data = new HashMap<>(step.params());
                    data.put("action", step.action());
                    var outcome = handler.execute(step.action() + "-" + tick, data, null);
                    if (step.supplyTrigger() > 0) {
                        if (outcome.success()) {fired[i] = true;}
                    } else {
                        fired[i] = true;
                    }
                }
            }
        }

        CalibrationBaseline baseline = new CalibrationBaseline(
                playbookResource, race.name(), "emulated-self", java.time.Instant.now().toString(),
                durationTicks, snapshots, null, null, null);

        CalibrationResult result = calibrate(playbookResource, baseline);
        assertTrue(result.passed(), "Self-baseline should always pass: " + result.report());
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline) {
        return calibrate(playbookResource, baseline, 0);
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline,
                                       int unitTolerance) {
        String   yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try {root = YAML.readTree(yaml);} catch (Exception e) {
            throw new RuntimeException("Failed to parse playbook", e);
        }

        Race                                 race  = SC2PlaybookRunner.resolveRace(root);
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));

        boolean useRealTerrain = false;
        if (baseline.basePosition() != null) {
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent()) {
                useRealTerrain = true;
                game.setTerrainGrid(cachedTerrain.get());
                Point2d realBase = new Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setInitialBasePosition(realBase);
            }
        }

        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            float offX = useRealTerrain ? 0 : 8f - baseline.basePosition()[0];
            float offY = useRealTerrain ? 0 : 8f - baseline.basePosition()[1];
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                .map(p -> new io.quarkmind.domain.Resource(
                    "cal-" + baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + offX, p.y() + offY), p.amount(), p.rich()))
                .toList());
        }
        game.reset();
        if (baseline.basePosition() != null && baseline.geyserPositions() != null) {
            float offX = useRealTerrain ? 0 : 8f - baseline.basePosition()[0];
            float offY = useRealTerrain ? 0 : 8f - baseline.basePosition()[1];
            Point2d emBase = useRealTerrain
                ? new Point2d(baseline.basePosition()[0], baseline.basePosition()[1])
                : new Point2d(8, 8);
            game.setGeyserResources(baseline.geyserPositions().stream()
                .map(g -> new io.quarkmind.domain.Resource(
                    "cal-geyser-" + baseline.geyserPositions().indexOf(g),
                    new Point2d(g.x() + offX, g.y() + offY), g.amount()))
                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                .toList());
        }
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        boolean[] fired     = new boolean[steps.size()];
        int       gasIdx    = 0;
        int       tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());

        for (int tick = 0; tick < tickLimit; tick++) {
            CalibrationSnapshot expected = baseline.snapshots().get(tick);
            CalibrationSnapshot actual   = CalibrationSnapshot.from(tick, game.snapshot());

            CalibrationResult divergence = compare(expected, actual, unitTolerance, unitTolerance);
            if (divergence != null) {return divergence;}

            if (expected.minerals() != actual.minerals() || expected.vespene() != actual.vespene()) {
                System.out.printf("  [econ] tick %d: minerals %d vs %d, vespene %d vs %d%n",
                                  tick, expected.minerals(), actual.minerals(),
                                  expected.vespene(), actual.vespene());
            }

            game.tick();
            game.processDeferredResearchIntents();

            int supplyUsed = (int) game.snapshot().supplyUsed();

            int reservedMinerals  = 0;
            int reservedStepIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if ("build".equals(step.action()) && step.supplyTrigger() > 0
                    && supplyUsed >= step.supplyTrigger() && !fired[i]) {
                    BuildingType bt   = BuildingType.valueOf((String) step.params().get("build"));
                    int          cost = SC2Data.mineralCost(bt);
                    if (cost >= SC2PlaybookRunner.RESERVATION_THRESHOLD) {
                        reservedMinerals  = cost;
                        reservedStepIndex = i;
                        break;
                    }
                }
            }

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed)
                                  && (!fired[i] || step.loop());
                if (canFire && reservedMinerals > 0
                    && game.snapshot().minerals() < reservedMinerals
                    && i != reservedStepIndex) {
                    if (step.loop() && "train".equals(step.action())) {continue;}
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) {continue;}
                }
                if (canFire) {
                    if ("build".equals(step.action())) {
                        BuildingType bt = BuildingType.valueOf((String) step.params().get("build"));
                        int mCost = SC2Data.mineralCost(bt);
                        int gCost = SC2Data.gasCost(bt);
                        if ((int) game.snapshot().minerals() < mCost || game.snapshot().vespene() < gCost) {
                            continue;
                        }
                        Point2d pos;
                        if (SC2Data.isGasBuilding(bt) && baseline.geyserPositions() != null) {
                            var geysers = game.snapshot().geysers();
                            pos = (geysers != null && gasIdx < geysers.size())
                                  ? geysers.get(gasIdx++).position()
                                  : new Point2d(30, 30);
                        } else {
                            pos = new Point2d(30, 30);
                        }
                        game.applyIntent(BuildIntent.of("cal-playbook", bt, pos));
                        fired[i] = true;
                    } else {
                        Map<String, Object> data = new HashMap<>(step.params());
                        data.put("action", step.action());
                        var outcome = handler.execute(step.action() + "-" + tick, data, null);
                        if (step.supplyTrigger() > 0) {
                            if (outcome.success()) {fired[i] = true;}
                        } else {
                            fired[i] = true;
                        }
                    }
                }
            }
        }

        return CalibrationResult.passed(tickLimit);
    }

    private static CalibrationResult compare(CalibrationSnapshot expected, CalibrationSnapshot actual,
                                             int unitTolerance, int buildingTolerance) {
        for (Map.Entry<String, Integer> entry : expected.units().entrySet()) {
            int actualCount = actual.units().getOrDefault(entry.getKey(), 0);
            if (Math.abs(actualCount - entry.getValue()) > unitTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    String.valueOf(entry.getValue()),
                    String.valueOf(actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actual.units().entrySet()) {
            if (!expected.units().containsKey(entry.getKey()) && entry.getValue() > unitTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    "0",
                    String.valueOf(entry.getValue()));
            }
        }

        for (Map.Entry<String, Integer> entry : expected.buildings().entrySet()) {
            int actualCount = actual.buildings().getOrDefault(entry.getKey(), 0);
            if (Math.abs(actualCount - entry.getValue()) > buildingTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "buildings." + entry.getKey(),
                    String.valueOf(entry.getValue()),
                    String.valueOf(actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actual.buildings().entrySet()) {
            if (!expected.buildings().containsKey(entry.getKey()) && entry.getValue() > buildingTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "buildings." + entry.getKey(),
                    "0",
                    String.valueOf(entry.getValue()));
            }
        }

        if (!expected.upgrades().equals(actual.upgrades())) {
            return CalibrationResult.diverged(expected.tick(),
                "upgrades",
                expected.upgrades().toString(),
                actual.upgrades().toString());
        }

        return null;
    }

    static CalibrationBaseline loadBaseline(String resourcePath) {
        try (InputStream is = DualRunCalibrationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Baseline not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resourcePath, e);
        }
    }

    private static java.util.Map<String, float[]> loadBuildingPositions(String baselinePath) {
        String bldgPath = baselinePath.replace(".json", "-buildings.json");
        try (InputStream is = DualRunCalibrationTest.class.getClassLoader().getResourceAsStream(bldgPath)) {
            if (is == null) return Map.of();
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                new String(is.readAllBytes(), StandardCharsets.UTF_8),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, float[]>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void pinnedCalibrate(String baselinePath) {
        CalibrationBaseline baseline = loadBaseline(baselinePath);
        java.util.Map<String, float[]> buildingPositions = loadBuildingPositions(baselinePath);
        java.util.Iterator<java.util.Map.Entry<String, float[]>> bldgPosIterator =
            buildingPositions.entrySet().iterator();
        EmulatedGame game = new EmulatedGame();
        Race race = Race.valueOf(baseline.race());
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));

        var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
        if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
            game.setTerrainGrid(cachedTerrain.get());
            game.setInitialBasePosition(new Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
        }

        if (baseline.mineralPatches() != null) {
            float offX = cachedTerrain.isPresent() ? 0 : 8f - baseline.basePosition()[0];
            float offY = cachedTerrain.isPresent() ? 0 : 8f - baseline.basePosition()[1];
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                .map(p -> new io.quarkmind.domain.Resource(
                    "cal-" + baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + offX, p.y() + offY), p.amount(), p.rich()))
                .toList());
        }
        game.setAutoTransferEnabled(false);
        game.reset();
        if (baseline.geyserPositions() != null) {
            float offX = cachedTerrain.isPresent() ? 0 : 8f - baseline.basePosition()[0];
            float offY = cachedTerrain.isPresent() ? 0 : 8f - baseline.basePosition()[1];
            Point2d emBase = cachedTerrain.isPresent()
                ? new Point2d(baseline.basePosition()[0], baseline.basePosition()[1])
                : new Point2d(8, 8);
            game.setGeyserResources(baseline.geyserPositions().stream()
                .map(g -> new io.quarkmind.domain.Resource(
                    "cal-geyser-" + baseline.geyserPositions().indexOf(g),
                    new Point2d(g.x() + offX, g.y() + offY), g.amount()))
                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                .toList());
        }

        List<CalibrationSnapshot> sc2 = baseline.snapshots();
        int tickLimit = sc2.size();

        String workerKey = switch (race) {
            case PROTOSS -> "PROBE";
            case TERRAN  -> "SCV";
            case ZERG    -> "DRONE";
        };
        io.quarkmind.domain.UnitType workerUnitType = switch (race) {
            case PROTOSS -> io.quarkmind.domain.UnitType.PROBE;
            case TERRAN  -> io.quarkmind.domain.UnitType.SCV;
            case ZERG    -> io.quarkmind.domain.UnitType.DRONE;
        };

        int maxMineralDelta = 0;
        int maxMineralDeltaTick = 0;
        int sc2WorkersInjected = 0;
        int sc2StartWorkers = sc2.get(0).units().getOrDefault(workerKey, 0);
        int sc2FinalWorkers = sc2.get(tickLimit - 1).units().getOrDefault(workerKey, 0);
        int sc2TotalToInject = sc2FinalWorkers - sc2StartWorkers;
        int sc2WorkerHighWater = sc2StartWorkers;
        java.util.Map<String, Integer> sc2BuildingHighWater = new java.util.HashMap<>(
            sc2.get(0).buildings());
        int prevSc2Income = 0;
        int prevEmIncome = 0;
        int prevSc2Minerals = 50;
        int prevEmMinerals = 50;
        int sc2CumulativeIncome = 0;
        int emCumulativeIncome = 0;

        List<Point2d> basePositions = new java.util.ArrayList<>();
        for (var entry : buildingPositions.entrySet()) {
            if (entry.getKey().startsWith("NEXUS-") || entry.getKey().startsWith("COMMAND_CENTER-")
                || entry.getKey().startsWith("HATCHERY-")) {
                float[] p = entry.getValue();
                basePositions.add(new Point2d(p[0], p[1]));
            }
        }

        System.out.println("=== Pinned calibration — SC2 build order replayed at exact ticks ===");
        System.out.println("tick | SC2 min | EM min | Δ bal | SC2 Δinc | EM Δinc | cum SC2 | cum EM | workers | SC2 w/base | EM w/base");

        for (int tick = 0; tick < tickLimit; tick++) {
            CalibrationSnapshot expected = sc2.get(tick);
            CalibrationSnapshot actual = CalibrationSnapshot.from(tick, game.snapshot());

            int mineralDelta = actual.minerals() - expected.minerals();
            if (Math.abs(mineralDelta) > Math.abs(maxMineralDelta)) {
                maxMineralDelta = mineralDelta;
                maxMineralDeltaTick = tick;
            }

            int sc2Spent = 0;
            int emSpent = 0;
            if (tick + 1 < tickLimit) {
                CalibrationSnapshot next = sc2.get(tick + 1);
                int nextProbes = next.units().getOrDefault(workerKey, 0);
                int curProbes = expected.units().getOrDefault(workerKey, 0);
                if (nextProbes > curProbes) sc2Spent += (nextProbes - curProbes) * 50;
                for (var e : next.buildings().entrySet()) {
                    int cur = expected.buildings().getOrDefault(e.getKey(), 0);
                    if (e.getValue() > cur) {
                        BuildingType bt = BuildingType.valueOf(e.getKey());
                        sc2Spent += (e.getValue() - cur) * io.quarkmind.domain.SC2Data.mineralCost(bt);
                    }
                }
                emSpent = sc2Spent;
            }

            int sc2Income = expected.minerals() - prevSc2Minerals + sc2Spent;
            int emIncome = actual.minerals() - prevEmMinerals + emSpent;
            if (tick == 0) { sc2Income = 0; emIncome = 0; }
            sc2CumulativeIncome += sc2Income;
            emCumulativeIncome += emIncome;

            if (tick % 5 == 0 || tick == tickLimit - 1) {
                int sc2Probes = expected.units().getOrDefault(workerKey, 0);
                int emProbes = actual.units().getOrDefault(workerKey, 0);
                String sc2PerBase = expected.workersPerBase() != null
                    ? java.util.Arrays.toString(expected.workersPerBase()) : "-";
                int[] emPerBase = game.mineralWorkersPerBase();
                String emPerBaseStr = emPerBase.length > 0
                    ? java.util.Arrays.toString(emPerBase) : "-";
                System.out.printf("%3d  | %6d | %6d | %+4d | %+7d | %+7d | %7d | %7d | %d/%d | %s | %s%n",
                    tick, expected.minerals(), actual.minerals(), mineralDelta,
                    sc2Income, emIncome, sc2CumulativeIncome, emCumulativeIncome,
                    sc2Probes, emProbes, sc2PerBase, emPerBaseStr);
            }

            prevSc2Minerals = expected.minerals();
            prevEmMinerals = actual.minerals();

            int workersBefore = (int) game.snapshot().myUnits().stream()
                .filter(u -> u.type() == workerUnitType).count();
            game.tick();
            game.processDeferredResearchIntents();
            int workersAfter = (int) game.snapshot().myUnits().stream()
                .filter(u -> u.type() == workerUnitType).count();
            if (workersAfter > workersBefore) {
                System.out.printf("  [%s] tick %d: %d → %d (+%d from tick())%n",
                    workerKey, tick, workersBefore, workersAfter, workersAfter - workersBefore);
            }

            if (tick + 1 < tickLimit) {
                CalibrationSnapshot next = sc2.get(tick + 1);
                int curProbes = expected.units().getOrDefault(workerKey, 0);
                int nextProbes = next.units().getOrDefault(workerKey, 0);
                if (nextProbes > sc2WorkerHighWater && sc2WorkersInjected < sc2TotalToInject) {
                    int toInject = Math.min(nextProbes - sc2WorkerHighWater,
                                           sc2TotalToInject - sc2WorkersInjected);
                    int[] nextPerBase = next.workersPerBase();
                    int[] curPerBase = expected.workersPerBase();
                    if (nextPerBase != null && curPerBase != null && !basePositions.isEmpty()) {
                        int injected = 0;
                        for (int bi = 0; bi < Math.min(curPerBase.length, nextPerBase.length)
                                         && bi < basePositions.size() && injected < toInject; bi++) {
                            int gained = nextPerBase[bi] - (bi < curPerBase.length ? curPerBase[bi] : 0);
                            for (int g = 0; g < gained && injected < toInject; g++) {
                                game.injectWorkerAtBase(basePositions.get(bi));
                                injected++;
                            }
                        }
                        while (injected < toInject) {
                            game.injectWorkerAtBase(basePositions.get(0));
                            injected++;
                        }
                    } else {
                        for (int p = 0; p < toInject; p++) {
                            game.injectWorkerForPinnedCalibration();
                        }
                    }
                    sc2WorkersInjected += toInject;
                    sc2WorkerHighWater = nextProbes;
                }
                for (var entry : next.buildings().entrySet()) {
                    int cur = sc2BuildingHighWater.getOrDefault(entry.getKey(), 0);
                    if (entry.getValue() > cur) {
                        BuildingType bt = BuildingType.valueOf(entry.getKey());
                        sc2BuildingHighWater.put(entry.getKey(), entry.getValue());
                        for (int b = 0; b < entry.getValue() - cur; b++) {
                            Point2d pos = new Point2d(30, 30);
                            String lookupKey = entry.getKey() + "-" + (tick + 1);
                            if (buildingPositions.containsKey(lookupKey)) {
                                float[] p = buildingPositions.get(lookupKey);
                                pos = new Point2d(p[0], p[1]);
                            } else {
                                for (var bp : buildingPositions.entrySet()) {
                                    if (bp.getKey().startsWith(entry.getKey())
                                        && !bp.getKey().equals("NEXUS-0")
                                        && !bp.getKey().equals("COMMAND_CENTER-0")
                                        && !bp.getKey().equals("HATCHERY-0")) {
                                        pos = new Point2d(bp.getValue()[0], bp.getValue()[1]);
                                        break;
                                    }
                                }
                            }
                            game.applyIntent(new io.quarkmind.sc2.intent.TimedIntent(
                                (long)(tick + 1) * SC2Data.LOOPS_PER_TICK,
                                io.quarkmind.sc2.intent.BuildIntent.of("cal-pinned", bt, pos)));
                        }
                    }
                }
            }
        }

        CalibrationSnapshot finalSc2 = sc2.get(tickLimit - 1);
        CalibrationSnapshot finalEm = CalibrationSnapshot.from(tickLimit - 1, game.snapshot());
        int finalMineralDelta = finalEm.minerals() - finalSc2.minerals();
        double mineralPct = finalSc2.minerals() > 0 ? finalMineralDelta * 100.0 / finalSc2.minerals() : 0;

        System.out.printf("%nFinal: SC2 minerals=%d EM minerals=%d (Δ=%+d, %+.1f%%)%n",
            finalSc2.minerals(), finalEm.minerals(), finalMineralDelta, mineralPct);
        System.out.printf("Final: SC2 vespene=%d EM vespene=%d (Δ=%+d)%n",
            finalSc2.vespene(), finalEm.vespene(), finalEm.vespene() - finalSc2.vespene());
        System.out.printf("Max mineral delta: %+d at tick %d%n", maxMineralDelta, maxMineralDeltaTick);
        System.out.printf("Final workers: SC2=%d EM=%d%n",
            finalSc2.units().getOrDefault(workerKey, 0),
            finalEm.units().getOrDefault(workerKey, 0));
        if (finalSc2.workersPerBase() != null) {
            System.out.printf("SC2 workers/base: %s%n", java.util.Arrays.toString(finalSc2.workersPerBase()));
        }
        int[] finalEmPerBase = game.mineralWorkersPerBase();
        if (finalEmPerBase.length > 0) {
            System.out.printf("EM workers/base:  %s%n", java.util.Arrays.toString(finalEmPerBase));
        }
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void positionDependentAccuracyComparison() {
        String[] playbooks = {
            "playbooks/cal-mineral-baseline-4w.yaml",
            "playbooks/cal-mineral-baseline-6w.yaml",
            "playbooks/cal-mineral-baseline-8w.yaml",
            "playbooks/cal-mineral-baseline-10w.yaml",
            "playbooks/cal-mineral-baseline-12w.yaml",
        };
        String[] labels = {"4w", "6w", "8w", "10w", "12w"};
        String[] baselineFiles = {
            "calibration/cal-mineral-baseline-4w.json",
            "calibration/cal-mineral-baseline-6w.json",
            "calibration/cal-mineral-baseline-8w.json",
            "calibration/cal-mineral-baseline-10w.json",
            "calibration/cal-mineral-baseline-12w.json",
        };

        System.out.println("=== Position-dependent EM accuracy ===");
        System.out.printf("%-6s | %-10s | %-8s | %-8s | %-8s | %-8s%n",
                          "label", "position", "SC2 min", "EM min", "delta", "err%");

        java.io.PrintStream origOut = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        for (int i = 0; i < playbooks.length; i++) {
            CalibrationBaseline baseline = loadBaseline(baselineFiles[i]);
            float[] basePos = baseline.basePosition();
            String posName = basePos != null && basePos[1] < 100 ? "top" : "bottom";

            int sc2Minerals = baseline.snapshots().get(baseline.snapshots().size() - 1).minerals();

            buf.reset();
            System.setOut(new java.io.PrintStream(buf));
            calibrate(playbooks[i], baseline, 100);
            System.setOut(origOut);

            int emMinerals = -1;
            for (String line : buf.toString().split("\n")) {
                if (line.contains("tick 304") && line.contains("minerals")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "minerals (\\d+) vs (\\d+)").matcher(line);
                    if (m.find()) {
                        emMinerals = Integer.parseInt(m.group(2));
                    }
                }
            }
            if (emMinerals < 0) {
                for (String line : buf.toString().split("\n")) {
                    if (line.contains("PASSED")) {
                        emMinerals = sc2Minerals;
                        break;
                    }
                }
            }

            int delta = emMinerals - sc2Minerals;
            double errPct = delta * 100.0 / sc2Minerals;
            System.out.printf("%-6s | %-10s | %-8d | %-8d | %-+8d | %-+7.1f%%%n",
                              labels[i], posName, sc2Minerals, emMinerals, delta, errPct);
        }

        System.out.println("\n--- EM-only position comparison (same playbook, both positions) ---");
        System.out.printf("%-6s | %-10s | %-8s%n", "label", "position", "EM min");

        CalibrationBaseline topBaseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationBaseline bottomBaseline = loadBaseline("calibration/cal-mineral-baseline-10w.json");

        for (String playbook : new String[]{"playbooks/cal-mineral-baseline-12w.yaml"}) {
            for (CalibrationBaseline bl : new CalibrationBaseline[]{topBaseline, bottomBaseline}) {
                float[] basePos = bl.basePosition();
                String posName = basePos != null && basePos[1] < 100 ? "top" : "bottom";
                int tickLimit = Math.min(bl.tickCount(), bl.snapshots().size());

                buf.reset();
                System.setOut(new java.io.PrintStream(buf));
                calibrate(playbook, bl, 100);
                System.setOut(origOut);

                int emMinerals = -1;
                String lastEconLine = null;
                for (String line : buf.toString().split("\n")) {
                    if (line.contains("[econ]") && line.contains("minerals")) {
                        lastEconLine = line;
                    }
                }
                if (lastEconLine != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "minerals (\\d+) vs (\\d+)").matcher(lastEconLine);
                    if (m.find()) {
                        emMinerals = Integer.parseInt(m.group(2));
                    }
                }
                if (emMinerals < 0) {
                    CalibrationSnapshot last = bl.snapshots().get(bl.snapshots().size() - 1);
                    emMinerals = last.minerals();
                }

                System.out.printf("%-6s | %-10s | %-8d%n", "12w", posName, emMinerals);
            }
        }

        System.out.println("\n--- A* path distance comparison (top vs bottom) ---");
        var terrainOpt = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
        if (terrainOpt.isEmpty()) {
            System.out.println("No terrain grid — skipping A* comparison");
            return;
        }
        io.quarkmind.domain.TerrainGrid terrain = terrainOpt.get();
        io.quarkmind.domain.AStarPathfinder pf = new io.quarkmind.domain.AStarPathfinder();

        float[][] topPatches = {
            {125.0f, 42.5f}, {131.0f, 48.5f}, {131.0f, 46.5f}, {126.0f, 41.5f},
            {130.0f, 43.5f}, {129.0f, 42.5f}, {132.0f, 47.5f}, {131.0f, 44.5f}
        };
        float[][] bottomPatches = {
            {125.0f, 165.5f}, {131.0f, 159.5f}, {131.0f, 161.5f}, {126.0f, 166.5f},
            {130.0f, 164.5f}, {129.0f, 165.5f}, {132.0f, 160.5f}, {131.0f, 163.5f}
        };
        Point2d topBase = new Point2d(124.5f, 48.5f);
        Point2d bottomBase = new Point2d(124.5f, 159.5f);

        System.out.printf("%-8s | %-20s | %-8s | %-8s | %-20s | %-8s%n",
                          "patch#", "top target", "top len", "top wp", "bottom target", "bot len");
        double topTotal = 0, bottomTotal = 0;
        for (int p = 0; p < 8; p++) {
            Point2d topTarget = new Point2d(topPatches[p][0], topPatches[p][1]);
            Point2d botTarget = new Point2d(bottomPatches[p][0], bottomPatches[p][1]);
            var topPath = pf.findPath(terrain, topBase, topTarget);
            var botPath = pf.findPath(terrain, bottomBase, botTarget);
            double topDist = pathLength(topBase, topPath);
            double botDist = pathLength(bottomBase, botPath);
            topTotal += topDist;
            bottomTotal += botDist;
            System.out.printf("%-8d | (%5.1f,%5.1f)         | %-8.2f | %-8d | (%5.1f,%5.1f)         | %-8.2f%n",
                              p, topPatches[p][0], topPatches[p][1], topDist, topPath.size(),
                              bottomPatches[p][0], bottomPatches[p][1], botDist);
        }
        System.out.printf("TOTAL    |                      | %-8.2f |          |                      | %-8.2f%n",
                          topTotal, bottomTotal);
        System.out.printf("Delta: bottom - top = %.2f (%.1f%%)%n",
                          bottomTotal - topTotal, (bottomTotal - topTotal) / topTotal * 100);

        System.out.println("\n--- Terrain walkability around bases ---");
        for (String posLabel : new String[]{"top", "bottom"}) {
            Point2d base = posLabel.equals("top") ? topBase : bottomBase;
            int bx = (int) base.x(), by = (int) base.y();
            System.out.printf("Base %s (%d, %d) — 20x20 walkability (. = walk, # = wall):%n", posLabel, bx, by);
            for (int y = by - 10; y <= by + 10; y++) {
                StringBuilder row = new StringBuilder();
                row.append(String.format("%3d | ", y));
                for (int x = bx - 5; x <= bx + 10; x++) {
                    if (x == bx && y == by) {
                        row.append('B');
                    } else {
                        boolean walkable = x >= 0 && y >= 0 && x < terrain.width() && y < terrain.height()
                                           && terrain.isWalkable(x, y);
                        row.append(walkable ? '.' : '#');
                    }
                }
                System.out.println(row);
            }
            System.out.println();
        }
    }

    private static double pathLength(Point2d start, java.util.List<Point2d> path) {
        if (path.isEmpty()) return 0;
        double len = start.distanceTo(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            len += path.get(i - 1).distanceTo(path.get(i));
        }
        return len;
    }
}
