package io.quarkmind.sc2.replay;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Tag("diagnostic")
class OwnReplayCalibrationTest {

    private static final Path OWN_REPLAYS_DIR = Path.of("replays/own");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);

    static boolean ownReplaysExist() {
        if (!Files.isDirectory(OWN_REPLAYS_DIR)) return false;
        try (var stream = Files.list(OWN_REPLAYS_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("ownReplaysExist")
    void economyCalibration() throws Exception {
        var replays = Files.list(OWN_REPLAYS_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted().toList();

        for (Path replayPath : replays) {
            System.out.printf("%n=== %s ===%n", replayPath.getFileName());

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    DivergenceReport report = ReplayValidationHarness.run(
                        replayPath, playerId, TICKS_PER_MINUTE * 6);

                    System.out.printf("%nPlayer %d:%n", playerId);

                    for (int min : new int[]{1, 2, 3, 4, 5}) {
                        int tickIndex = min * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        var snap = report.ticks().get(tickIndex);

                        System.out.printf("%n  --- %d-min ---%n", min);

                        System.out.printf("  Units: GT=%d  EM=%d  delta=%d%n",
                            snap.groundTruthUnits(), snap.emulatedUnits(), snap.unitDelta());
                        System.out.printf("  Buildings: GT=%d  EM=%d  delta=%d%n",
                            snap.groundTruthBuildings(), snap.emulatedBuildings(), snap.buildingDelta());
                        System.out.printf("  Minerals: GT=%d  EM=%d  delta=%d%n",
                            snap.groundTruthMinerals(), snap.emulatedMinerals(), snap.mineralDelta());

                        if (!snap.groundTruthUnitsByType().isEmpty()) {
                            System.out.println("  Units by type:");
                            for (var e : snap.groundTruthUnitsByType().entrySet()) {
                                int em = snap.emulatedUnitsByType().getOrDefault(e.getKey(), 0);
                                if (e.getValue() != em) {
                                    System.out.printf("    %-20s GT=%3d  EM=%3d  %s%n",
                                        e.getKey(), e.getValue(), em,
                                        em < e.getValue() ? "UNDER" : "OVER");
                                }
                            }
                            for (var e : snap.emulatedUnitsByType().entrySet()) {
                                if (!snap.groundTruthUnitsByType().containsKey(e.getKey()) && e.getValue() > 0) {
                                    System.out.printf("    %-20s GT=%3d  EM=%3d  EXTRA%n",
                                        e.getKey(), 0, e.getValue());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.printf("Player %d: FAILED — %s%n", playerId, e.getMessage());
                }
            }
        }
    }
}
