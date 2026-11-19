package io.quarkmind.sc2.real;

import io.quarkmind.sc2.playbook.CalibrationBaseline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("sc2")
class PlaybookSC2RecorderTest {

    @Test
    void recordProtossProbeEconomy() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                "StarCraft II not installed — skipping live SC2 test");
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

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
            transport, "playbooks/economy-only-protoss.yaml");

        transport.runGameLoop(adapter);

        while (!transport.isRunning()) { Thread.sleep(50); }
        while (transport.isRunning()) { Thread.sleep(100); }

        assertFalse(adapter.snapshots().isEmpty(), "Should have recorded snapshots");
        System.out.printf("[Recorder] Captured %d snapshots%n", adapter.snapshots().size());

        CalibrationBaseline baseline = adapter.buildBaseline(transport.baseBuild());
        String json = baseline.toJson();

        Path output = Path.of("src/test/resources/calibration/protoss-probe-economy.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
        System.out.printf("[Recorder] Baseline written to %s (%d bytes)%n",
            output, json.length());
    }

    @Test
    void recordCalMineralBaseline12w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-baseline-12w.yaml", "calibration/cal-mineral-baseline-12w.json");
    }

    @Test
    void recordCalMineralSaturated16w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-saturated-16w.yaml", "calibration/cal-mineral-saturated-16w.json");
    }

    @Test
    void recordCalMineralSaturated24w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-saturated-24w.yaml", "calibration/cal-mineral-saturated-24w.json");
    }

    @Test
    void recordCalGasBaseline3w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-gas-baseline-3w.yaml", "calibration/cal-gas-baseline-3w.json");
    }

    @Test
    void recordCalGasDual6w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-gas-dual-6w.yaml", "calibration/cal-gas-dual-6w.json");
    }

    @Test
    void recordCalMineralBaselineTerran() throws Exception {
        recordPlaybook("TERRAN", "playbooks/cal-mineral-baseline-terran.yaml", "calibration/cal-mineral-baseline-terran.json");
    }

    @Test
    void recordCalMuleIsolation() throws Exception {
        recordPlaybook("TERRAN", "playbooks/cal-mule-isolation.yaml", "calibration/cal-mule-isolation.json");
    }

    @Test
    void recordCalMineralBaselineZerg() throws Exception {
        recordPlaybook("ZERG", "playbooks/cal-mineral-baseline-zerg.yaml", "calibration/cal-mineral-baseline-zerg.json");
    }

    @Test
    void recordCalExpansionTransfer() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-expansion-transfer.yaml", "calibration/cal-expansion-transfer.json");
    }

    @Test
    void recordCalWorkerSpeedSingle() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-worker-speed-single.yaml", "calibration/cal-worker-speed-single.json");
    }

    @Test
    void recordCalWorkerCadence12w() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
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

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                transport, "playbooks/cal-mineral-baseline-12w.yaml");

        transport.runGameLoop(adapter);

        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        assertFalse(adapter.workerCadenceSnapshots().isEmpty(),
                    "Should have recorded worker cadence snapshots");
        assertFalse(adapter.snapshots().isEmpty(),
                    "Should have recorded calibration snapshots");

        io.quarkmind.sc2.playbook.WorkerCadenceBaseline cadenceBaseline =
                adapter.buildWorkerCadenceBaseline(transport.baseBuild());
        String cadenceJson   = cadenceBaseline.toJson();
        Path   cadenceOutput = Path.of("src/test/resources/calibration/cal-worker-cadence-12w.json");
        Files.createDirectories(cadenceOutput.getParent());
        Files.writeString(cadenceOutput, cadenceJson);
        System.out.printf("[Recorder] Worker cadence baseline written to %s (%d bytes)%n",
                          cadenceOutput, cadenceJson.length());

        CalibrationBaseline calBaseline = adapter.buildBaseline(transport.baseBuild());
        String              calJson     = calBaseline.toJson();
        Path                calOutput   = Path.of("src/test/resources/calibration/cal-mineral-baseline-12w.json");
        Files.writeString(calOutput, calJson);
        System.out.printf("[Recorder] Calibration baseline written to %s (%d bytes)%n",
                          calOutput, calJson.length());

        System.out.printf("[Recorder] Both baselines from same game run — %d ticks%n",
                          adapter.snapshots().size());
    }

    @Test
    void recordCalWorkerCadence16w() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
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

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                transport, "playbooks/cal-mineral-saturated-16w.yaml");

        transport.runGameLoop(adapter);

        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        assertFalse(adapter.workerCadenceSnapshots().isEmpty(),
                    "Should have recorded worker cadence snapshots");
        assertFalse(adapter.snapshots().isEmpty(),
                    "Should have recorded calibration snapshots");

        io.quarkmind.sc2.playbook.WorkerCadenceBaseline cadenceBaseline =
                adapter.buildWorkerCadenceBaseline(transport.baseBuild());
        String cadenceJson   = cadenceBaseline.toJson();
        Path   cadenceOutput = Path.of("src/test/resources/calibration/cal-worker-cadence-16w.json");
        Files.createDirectories(cadenceOutput.getParent());
        Files.writeString(cadenceOutput, cadenceJson);
        System.out.printf("[Recorder] Worker cadence baseline written to %s (%d bytes)%n",
                          cadenceOutput, cadenceJson.length());

        CalibrationBaseline calBaseline = adapter.buildBaseline(transport.baseBuild());
        String              calJson     = calBaseline.toJson();
        Path                calOutput   = Path.of("src/test/resources/calibration/cal-mineral-saturated-16w.json");
        Files.writeString(calOutput, calJson);
        System.out.printf("[Recorder] Calibration baseline written to %s (%d bytes)%n",
                          calOutput, calJson.length());

        System.out.printf("[Recorder] Both baselines from same game run — %d ticks%n",
                          adapter.snapshots().size());
    }


    @Test
    void recordCalMineralBaseline8w() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
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

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                transport, "playbooks/cal-mineral-baseline-8w.yaml");

        transport.runGameLoop(adapter);

        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        assertFalse(adapter.workerCadenceSnapshots().isEmpty(),
                    "Should have recorded worker cadence snapshots");
        assertFalse(adapter.snapshots().isEmpty(),
                    "Should have recorded calibration snapshots");

        io.quarkmind.sc2.playbook.WorkerCadenceBaseline cadenceBaseline =
                adapter.buildWorkerCadenceBaseline(transport.baseBuild());
        String cadenceJson   = cadenceBaseline.toJson();
        Path   cadenceOutput = Path.of("src/test/resources/calibration/cal-worker-cadence-8w.json");
        Files.createDirectories(cadenceOutput.getParent());
        Files.writeString(cadenceOutput, cadenceJson);
        System.out.printf("[Recorder] Worker cadence baseline written to %s (%d bytes)%n",
                          cadenceOutput, cadenceJson.length());

        CalibrationBaseline calBaseline = adapter.buildBaseline(transport.baseBuild());
        String              calJson     = calBaseline.toJson();
        Path                calOutput   = Path.of("src/test/resources/calibration/cal-mineral-baseline-8w.json");
        Files.writeString(calOutput, calJson);
        System.out.printf("[Recorder] Calibration baseline written to %s (%d bytes)%n",
                          calOutput, calJson.length());

        System.out.printf("[Recorder] Both baselines from same game run — %d ticks%n",
                          adapter.snapshots().size());
    }

    @Test
    void recordCalMineralBaseline4w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-baseline-4w.yaml", "calibration/cal-mineral-baseline-4w.json");
    }

    @Test
    void recordCalMineralBaseline6w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-baseline-6w.yaml", "calibration/cal-mineral-baseline-6w.json");
    }

    @Test
    void recordCalMineralBaseline10w() throws Exception {
        recordPlaybook("PROTOSS", "playbooks/cal-mineral-baseline-10w.yaml", "calibration/cal-mineral-baseline-10w.json");
    }


    @Test
    void recordMultiRunCadence12w() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
        int runs = 5;

        for (int run = 1; run <= runs; run++) {
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

            PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                    transport, "playbooks/cal-mineral-baseline-12w.yaml");

            transport.runGameLoop(adapter);
            while (!transport.isRunning()) {Thread.sleep(50);}
            while (transport.isRunning()) {Thread.sleep(100);}

            assertFalse(adapter.workerCadenceSnapshots().isEmpty(),
                        "Run " + run + ": should have recorded worker cadence snapshots");

            io.quarkmind.sc2.playbook.WorkerCadenceBaseline cadenceBaseline =
                    adapter.buildWorkerCadenceBaseline(transport.baseBuild());
            String cadenceJson   = cadenceBaseline.toJson();
            Path   cadenceOutput = Path.of("src/test/resources/calibration/multi-run/cal-worker-cadence-12w-run-" + run + ".json");
            Files.createDirectories(cadenceOutput.getParent());
            Files.writeString(cadenceOutput, cadenceJson);
            System.out.printf("[Recorder] Run %d/%d: Worker cadence written to %s (%d bytes, %d ticks)%n",
                              run, runs, cadenceOutput, cadenceJson.length(), adapter.snapshots().size());

            new ProcessBuilder("pkill", "-9", "-f", "SC2.app/Contents/MacOS/SC2").start().waitFor();
            Thread.sleep(3000);
        }
    }

    @Test
    void recordMultiRunGasDual6w() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
        int   runs         = 5;
        int[] vespeneAtEnd = new int[runs];

        for (int run = 1; run <= runs; run++) {
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

            PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                    transport, "playbooks/cal-gas-dual-6w.yaml");

            transport.runGameLoop(adapter);
            while (!transport.isRunning()) {Thread.sleep(50);}
            while (transport.isRunning()) {Thread.sleep(100);}

            assertFalse(adapter.snapshots().isEmpty(),
                        "Run " + run + ": should have recorded snapshots");

            CalibrationBaseline baseline = adapter.buildBaseline(transport.baseBuild());
            String              json     = baseline.toJson();
            Path                output   = Path.of("src/test/resources/calibration/multi-run/cal-gas-dual-6w-run-" + run + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, json);

            var lastSnapshot = adapter.snapshots().get(adapter.snapshots().size() - 1);
            vespeneAtEnd[run - 1] = lastSnapshot.vespene();
            System.out.printf("[Recorder] Run %d/%d: vespene=%d, minerals=%d (%d ticks, %d bytes)%n",
                              run, runs, lastSnapshot.vespene(), lastSnapshot.minerals(),
                              adapter.snapshots().size(), json.length());

            new ProcessBuilder("pkill", "-9", "-f", "SC2.app/Contents/MacOS/SC2").start().waitFor();
            Thread.sleep(3000);
        }

        double sum = 0;
        for (int v : vespeneAtEnd) {sum += v;}
        double mean     = sum / runs;
        double variance = 0;
        for (int v : vespeneAtEnd) {variance += (v - mean) * (v - mean);}
        double stddev   = Math.sqrt(variance / runs);
        double coeffVar = (stddev / mean) * 100.0;

        System.out.println("\n=== Multi-Run Gas Dual 6w Summary ===");
        System.out.printf("Runs: %d%n", runs);
        for (int i = 0; i < runs; i++) {
            System.out.printf("  Run %d: vespene=%d%n", i + 1, vespeneAtEnd[i]);
        }
        System.out.printf("Mean vespene: %.1f%n", mean);
        System.out.printf("Std dev: %.1f%n", stddev);
        System.out.printf("CV: %.2f%%%n", coeffVar);
        System.out.printf("Verdict: %s%n",
                          coeffVar > 1.0 ? "VARIANCE — target the mean" : "CONSISTENT — map-specific floor");
    }


    private void recordPlaybook(String race, String playbookResource, String outputPath) throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");
        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port                = 8168;
        transport.mapName                = "TorchesAIE_v4";
        transport.difficultyStr          = "VERY_EASY";
        transport.aiRaceStr              = "RANDOM";
        transport.botRaceStr             = race;
        transport.connectRetryCount      = 60;
        transport.connectRetryIntervalMs = 5000;
        transport.connect();
        transport.createGame();
        transport.joinGame();

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(
                transport, playbookResource);

        transport.runGameLoop(adapter);

        while (!transport.isRunning()) { Thread.sleep(50); }
        while (transport.isRunning()) { Thread.sleep(100); }

        assertFalse(adapter.snapshots().isEmpty(), "Should have recorded snapshots");
        System.out.printf("[Recorder] %s: Captured %d snapshots%n", playbookResource, adapter.snapshots().size());

        CalibrationBaseline baseline = adapter.buildBaseline(transport.baseBuild());
        String              json     = baseline.toJson();

        Path output = Path.of("src/test/resources/" + outputPath);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
        System.out.printf("[Recorder] Baseline written to %s (%d bytes)%n",
                          output, json.length());
    }

}
