package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates morph times using tracker events only — no game event correlation needed.
 * Measures the interval between UnitBorn(source) and UnitTypeChange(target) for the
 * same unit tag. Works with any replay that has tracker events, regardless of patch version.
 */
@Tag("diagnostic")
class TrackerOnlyMorphCalibrationTest {

    private static final Path TOURNAMENT_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");
    private static final Path AIARENA_DIR = Path.of("replays/aiarena_protoss");

    private static final Map<String, String> MORPH_SOURCE = Map.of(
        "Baneling", "Zergling",
        "Ravager", "Roach",
        "Lurker", "Hydralisk",
        "BroodLord", "Corruptor",
        "Overseer", "Overlord"
    );

    static boolean tournamentExists() { return Files.isDirectory(TOURNAMENT_DIR); }
    static boolean aiarenaExists() { return Files.isDirectory(AIARENA_DIR); }

    @Test
    @EnabledIf("tournamentExists")
    void calibrateFromTournament() throws Exception {
        var results = calibrateFromDir(TOURNAMENT_DIR, "Tournament", true);
        assertThat(results).as("Must calibrate at least one morph time").isNotEmpty();
    }

    @Test
    @EnabledIf("aiarenaExists")
    void calibrateFromAiArena() throws Exception {
        calibrateFromDir(AIARENA_DIR, "AI Arena", false);
    }

    private Map<String, Integer> calibrateFromDir(Path dir, String label, boolean recursive) throws Exception {
        Map<String, List<Integer>> morphTimes = new TreeMap<>();
        int replayCount = 0;
        var stream = recursive ? Files.walk(dir) : Files.list(dir);
        try (stream) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                try {
                    accumulateFromReplay(rp, morphTimes);
                    replayCount++;
                } catch (Exception e) { /* skip */ }
            }
        }
        System.out.printf("%n=== Tracker-Only Morph Calibration — %s (%d replays) ===%n", label, replayCount);
        Map<String, Integer> calibrated = new TreeMap<>();
        for (var entry : morphTimes.entrySet()) {
            String target = entry.getKey();
            List<Integer> times = entry.getValue();
            if (times.isEmpty()) continue;
            Map<Integer, Integer> freq = new TreeMap<>();
            for (int t : times) { freq.merge(t, 1, Integer::sum); }
            var best = freq.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            int modal = best.getKey();
            int count = best.getValue();
            System.out.printf("  %-12s  T_real = %d loops (%.1fs)  modal_count=%d  n=%d%n",
                target, modal, modal / 22.4, count, times.size());
            freq.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> System.out.printf("    %d loops (%.1fs): %d observations%n",
                    e.getKey(), e.getKey() / 22.4, e.getValue()));
            if (count >= 2) { calibrated.put(target, modal); }
        }
        return calibrated;
    }

    private void accumulateFromReplay(Path replayPath, Map<String, List<Integer>> morphTimes) {
        Replay replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.TRACKER_EVENTS));
        if (replay == null || replay.trackerEvents == null) return;
        Map<Long, String[]> unitTagInfo = new HashMap<>();
        for (Event raw : replay.trackerEvents.getEvents()) {
            if (raw.getId() == ITrackerEvents.ID_UNIT_BORN) {
                var ub = (IBaseUnitEvent) raw;
                String name = ub.getUnitTypeName().toString().trim();
                long tag = ((long) ub.getUnitTagIndex()) << 18 | ub.getUnitTagRecycle();
                unitTagInfo.put(tag, new String[]{name, String.valueOf(raw.getLoop())});
            }
        }
        for (Event raw : replay.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) continue;
            Object nameObj = raw.get("unitTypeName");
            if (nameObj == null) continue;
            String targetName = nameObj.toString();
            String expectedSource = MORPH_SOURCE.get(targetName);
            if (expectedSource == null) continue;
            Object tagIdxObj = raw.get("unitTagIndex");
            Object tagRecObj = raw.get("unitTagRecycle");
            if (tagIdxObj == null || tagRecObj == null) continue;
            int tagIdx = ((Number) tagIdxObj).intValue();
            int tagRec = ((Number) tagRecObj).intValue();
            long tag = ((long) tagIdx) << 18 | tagRec;
            String[] info = unitTagInfo.get(tag);
            if (info == null) continue;
            if (!expectedSource.equals(info[0])) continue;
            int morphTime = (int) (raw.getLoop() - Long.parseLong(info[1]));
            if (morphTime < 50 || morphTime > 2000) continue;
            morphTimes.computeIfAbsent(targetName, k -> new ArrayList<>()).add(morphTime);
        }
    }
}
