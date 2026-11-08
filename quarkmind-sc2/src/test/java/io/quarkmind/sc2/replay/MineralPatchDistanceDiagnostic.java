package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.List;

@Tag("diagnostic")
class MineralPatchDistanceDiagnostic {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void measureMineralPatchDistances() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().limit(30).toList();
        }

        DoubleSummaryStatistics allDists = new DoubleSummaryStatistics();
        DoubleSummaryStatistics closeDists = new DoubleSummaryStatistics();
        DoubleSummaryStatistics farDists = new DoubleSummaryStatistics();
        int totalBases = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);
                    gt.reset();
                    for (int t = 0; t < 5; t++) gt.tick();

                    var snapshot = gt.snapshot();
                    List<Resource> patches = gt.getMineralPatches();

                    for (var b : snapshot.myBuildings()) {
                        if (b.type().name().contains("NEXUS") || b.type().name().contains("COMMAND")
                            || b.type().name().contains("HATCHERY")) {
                            Point2d thPos = b.position();
                            List<Double> distances = new ArrayList<>();
                            for (Resource r : patches) {
                                double d = thPos.distanceTo(r.position());
                                if (d <= 12.0) {
                                    distances.add(d);
                                    allDists.accept(d);
                                }
                            }
                            if (!distances.isEmpty()) {
                                distances.sort(Double::compare);
                                totalBases++;
                                for (int i = 0; i < distances.size(); i++) {
                                    if (i < 4) closeDists.accept(distances.get(i));
                                    else farDists.accept(distances.get(i));
                                }
                            }
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Mineral Patch Distance Diagnostic ===");
        System.out.printf("Bases analysed: %d%n", totalBases);
        System.out.printf("Total patches measured: %d%n%n", (long) allDists.getCount());
        System.out.printf("All patches:   min=%.1f  avg=%.1f  max=%.1f%n",
                allDists.getMin(), allDists.getAverage(), allDists.getMax());
        System.out.printf("Close patches: min=%.1f  avg=%.1f  max=%.1f  (first 4 per base)%n",
                closeDists.getMin(), closeDists.getAverage(), closeDists.getMax());
        System.out.printf("Far patches:   min=%.1f  avg=%.1f  max=%.1f  (remaining)%n",
                farDists.getMin(), farDists.getAverage(), farDists.getMax());
        System.out.printf("%nCurrent PATCH_DISTANCE constant: 6.0%n");
        System.out.printf("Avg close patch distance: %.1f%n", closeDists.getAverage());
    }
}
