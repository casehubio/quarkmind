package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Full-replay divergence report — run with: mvn test -Preport
 * No assertions. Human-readable output to stdout.
 */
@Tag("report")
class ReplayValidationReportTest {

    @Test
    void fullReplayDivergenceReport() {
        Path replay = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
        DivergenceReport report = ReplayValidationHarness.run(replay, 1, Integer.MAX_VALUE);
        System.out.println(report.renderReport());
    }
}
