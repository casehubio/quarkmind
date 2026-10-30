package io.quarkmind.sc2.real;

import io.quarkmind.sc2.playbook.CalibrationBaseline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("sc2")
class PlaybookSC2RecorderTest {

    @Test
    void recordProtossProbeEconomy() throws Exception {
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

        while (transport.isRunning()) {
            Thread.sleep(100);
        }

        assertFalse(adapter.snapshots().isEmpty(), "Should have recorded snapshots");
        System.out.printf("[Recorder] Captured %d snapshots%n", adapter.snapshots().size());

        CalibrationBaseline baseline = adapter.buildBaseline("unknown");
        String json = baseline.toJson();

        Path output = Path.of("src/test/resources/calibration/protoss-probe-economy.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
        System.out.printf("[Recorder] Baseline written to %s (%d bytes)%n",
            output, json.length());
    }
}
