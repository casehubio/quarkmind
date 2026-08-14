package io.quarkmind.sc2.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Diagnostic: prints the (abilLink, abilCmdIndex) → unit-born correlation table for all
 * three races across all 30 IEM10 games, using narrow-window modal matching.
 *
 * Run with: mvn test -Pdiagnostic
 *
 * Constants hard-coded in IEM10CommandExtractor were derived from this output.
 * Expected top matches (IEM10 2016 build ~39948):
 *   Protoss: Probe=167/0, Stalker=164/1, Adept=164/6, Observer=166/1,
 *            Immortal=166/3, Disruptor=166/18, Phoenix=165/0, Tempest=165/9
 *   Zerg:    Drone=185/0, Zergling=185/1, Roach=185/9, Queen=235/0
 *   Terran:  SCV=147/0, Marine=151/0
 */
@Tag("diagnostic")
class IEM10AbilityDiscoveryTest {

    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");

    private static final Map<String, int[]> WINDOWS = new LinkedHashMap<>();
    static {
        WINDOWS.put("Probe",         new int[]{120,  400});
        WINDOWS.put("Stalker",       new int[]{544,  844});
        WINDOWS.put("Zealot",        new int[]{477,  777});
        WINDOWS.put("Adept",         new int[]{477,  777});
        WINDOWS.put("Immortal",      new int[]{746, 1046});
        WINDOWS.put("Observer",      new int[]{342,  642});
        WINDOWS.put("Disruptor",     new int[]{656,  956});
        WINDOWS.put("WarpPrism",     new int[]{450,  750});
        WINDOWS.put("Phoenix",       new int[]{377,  677});
        WINDOWS.put("Tempest",       new int[]{756, 1056});
        WINDOWS.put("Drone",         new int[]{200,  500});
        WINDOWS.put("Zergling",      new int[]{500,  850});
        WINDOWS.put("Roach",         new int[]{550,  850});
        WINDOWS.put("Mutalisk",      new int[]{600,  950});
        WINDOWS.put("Queen",         new int[]{650,  950});
        WINDOWS.put("SCV",           new int[]{200,  450});
        WINDOWS.put("Marine",        new int[]{350,  650});
        WINDOWS.put("Marauder",      new int[]{450,  750});
        WINDOWS.put("Medivac",       new int[]{550,  850});
    }

    @Test
    void printAbilityCorrelations() throws IOException {
        Map<String, Map<String, Map<String, Integer>>> results = new TreeMap<>();
        for (String r : List.of("Prot", "Zerg", "Terr"))
            results.put(r, new TreeMap<>());

        try (ZipArchiveInputStream outer = new ZipArchiveInputStream(
                Files.newInputStream(IEM10_ZIP), "UTF-8", true, true)) {
            ZipArchiveEntry outerEntry;
            while ((outerEntry = outer.getNextEntry()) != null) {
                if (!outerEntry.getName().endsWith("_data.zip")) continue;
                byte[] innerBytes = outer.readAllBytes();
                try (ZipInputStream inner = new ZipInputStream(new ByteArrayInputStream(innerBytes))) {
                    ZipEntry innerEntry;
                    while ((innerEntry = inner.getNextEntry()) != null) {
                        if (!innerEntry.getName().endsWith(".SC2Replay.json")) continue;
                        JsonNode root = new ObjectMapper().readTree(inner.readAllBytes());
                        processGame(root, results);
                    }
                }
            }
        }

        System.out.println("\n=== IEM10 Ability Discovery — narrow-window modal correlation ===");
        for (var raceEntry : results.entrySet()) {
            System.out.println("\n[" + raceEntry.getKey() + "]");
            for (var unitEntry : raceEntry.getValue().entrySet()) {
                var counts = new TreeMap<>(Comparator.comparingInt(
                    (String k) -> -unitEntry.getValue().getOrDefault(k, 0)));
                counts.putAll(unitEntry.getValue());
                var top = counts.entrySet().stream().limit(3).toList();
                if (top.isEmpty()) continue;
                System.out.printf("  %-16s best=%s(%d)  alts=%s%n",
                    unitEntry.getKey(),
                    top.get(0).getKey(), top.get(0).getValue(),
                    top.subList(1, top.size()));
            }
        }
    }

    private void processGame(JsonNode root,
                             Map<String, Map<String, Map<String, Integer>>> results) {
        Map<Integer, String> userToRace   = new HashMap<>();
        Map<Integer, String> playerToRace = new HashMap<>();
        for (JsonNode v : root.get("ToonPlayerDescMap")) {
            userToRace.put(v.get("userID").asInt(),    v.get("race").asText());
            playerToRace.put(v.get("playerID").asInt(), v.get("race").asText());
        }

        Map<String, List<Long>> bornByUnit = new HashMap<>();
        for (JsonNode e : root.get("trackerEvents")) {
            if (!"UnitBorn".equals(e.path("evtTypeName").asText())) continue;
            long loop = e.path("loop").asLong();
            if (loop == 0) continue;
            int pid  = e.path("controlPlayerId").asInt();
            String race = playerToRace.get(pid);
            String unit = e.path("unitTypeName").asText();
            if (race != null && WINDOWS.containsKey(unit))
                bornByUnit.computeIfAbsent(race + ":" + unit, k -> new ArrayList<>()).add(loop);
        }

        Map<String, List<long[]>> cmdByRace = new HashMap<>();
        for (JsonNode e : root.get("gameEvents")) {
            if (!"Cmd".equals(e.path("evtTypeName").asText())) continue;
            if (!isNoTarget(e)) continue;
            int uid = e.path("userid").path("userId").asInt();
            String race = userToRace.get(uid);
            if (race == null) continue;
            JsonNode abil = e.path("abil");
            if (abil.isMissingNode()) continue;
            int link = abil.path("abilLink").asInt(-1);
            int idx  = abil.path("abilCmdIndex").asInt(0);
            if (link < 0) continue;
            cmdByRace.computeIfAbsent(race, k -> new ArrayList<>())
                     .add(new long[]{e.path("loop").asLong(), link, idx});
        }

        for (var entry : bornByUnit.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String race = parts[0], unit = parts[1];
            int[] window = WINDOWS.get(unit);
            List<long[]> cmds = cmdByRace.getOrDefault(race, List.of());
            Map<String, Integer> unitMap = results.get(race)
                .computeIfAbsent(unit, k -> new HashMap<>());
            for (long bornLoop : entry.getValue()) {
                for (long[] cmd : cmds) {
                    long diff = bornLoop - cmd[0];
                    if (diff >= window[0] && diff <= window[1]) {
                        String key = cmd[1] + "/" + cmd[2];
                        unitMap.merge(key, 1, Integer::sum);
                    }
                }
            }
        }
    }

    private boolean isNoTarget(JsonNode event) {
        JsonNode data = event.path("data");
        if (data.isMissingNode()) return true;
        return data.has("None") && data.size() == 1;
    }
}
