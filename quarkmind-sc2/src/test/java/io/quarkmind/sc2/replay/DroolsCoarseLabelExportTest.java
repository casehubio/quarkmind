package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.ArchetypeCategory;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class DroolsCoarseLabelExportTest {

    private static final Map<String, UnitType> TRACKER_NAME_TO_UNIT = Map.ofEntries(
            Map.entry("Marine", UnitType.MARINE), Map.entry("Marauder", UnitType.MARAUDER),
            Map.entry("Reaper", UnitType.REAPER), Map.entry("Ghost", UnitType.GHOST),
            Map.entry("Hellion", UnitType.HELLION), Map.entry("HellionTank", UnitType.HELLBAT),
            Map.entry("SiegeTank", UnitType.SIEGE_TANK), Map.entry("Cyclone", UnitType.CYCLONE),
            Map.entry("Thor", UnitType.THOR), Map.entry("Medivac", UnitType.MEDIVAC),
            Map.entry("VikingFighter", UnitType.VIKING), Map.entry("Liberator", UnitType.LIBERATOR),
            Map.entry("Banshee", UnitType.BANSHEE), Map.entry("Raven", UnitType.RAVEN),
            Map.entry("Battlecruiser", UnitType.BATTLECRUISER), Map.entry("WidowMine", UnitType.WIDOW_MINE),
            Map.entry("Zergling", UnitType.ZERGLING), Map.entry("Baneling", UnitType.BANELING),
            Map.entry("Roach", UnitType.ROACH), Map.entry("Ravager", UnitType.RAVAGER),
            Map.entry("Hydralisk", UnitType.HYDRALISK), Map.entry("Lurker", UnitType.LURKER),
            Map.entry("Mutalisk", UnitType.MUTALISK), Map.entry("Corruptor", UnitType.CORRUPTOR),
            Map.entry("BroodLord", UnitType.BROOD_LORD), Map.entry("Infestor", UnitType.INFESTOR),
            Map.entry("SwarmHostMP", UnitType.SWARM_HOST), Map.entry("Ultralisk", UnitType.ULTRALISK),
            Map.entry("Viper", UnitType.VIPER), Map.entry("Queen", UnitType.QUEEN),
            Map.entry("Zealot", UnitType.ZEALOT), Map.entry("Stalker", UnitType.STALKER),
            Map.entry("Sentry", UnitType.SENTRY), Map.entry("Adept", UnitType.ADEPT),
            Map.entry("HighTemplar", UnitType.HIGH_TEMPLAR), Map.entry("DarkTemplar", UnitType.DARK_TEMPLAR),
            Map.entry("Archon", UnitType.ARCHON), Map.entry("Immortal", UnitType.IMMORTAL),
            Map.entry("Colossus", UnitType.COLOSSUS), Map.entry("Disruptor", UnitType.DISRUPTOR),
            Map.entry("Phoenix", UnitType.PHOENIX), Map.entry("Oracle", UnitType.ORACLE),
            Map.entry("VoidRay", UnitType.VOID_RAY), Map.entry("Carrier", UnitType.CARRIER),
            Map.entry("Tempest", UnitType.TEMPEST), Map.entry("Mothership", UnitType.MOTHERSHIP),
            Map.entry("WarpPrism", UnitType.WARP_PRISM)
    );

    private static final Path RECONSTITUTED_DIR = Path.of("../quarkmind-classifier/data/reconstituted");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double LOOPS_PER_SECOND = SC2Data.GAME_LOOPS_PER_SECOND;

    private static final Map<ArchetypeCategory, String> CATEGORY_TO_COARSE = Map.of(
            ArchetypeCategory.RUSH, "AGGRESSIVE",
            ArchetypeCategory.TIMING, "GROUND",
            ArchetypeCategory.HARASS, "TECH_AIR",
            ArchetypeCategory.MACRO, "MACRO",
            ArchetypeCategory.TECH, "TECH",
            ArchetypeCategory.COMPOSITION, "GROUND"
    );

    @Test
    @SuppressWarnings("unchecked")
    void exportCoarseLabels() throws Exception {
        assertTrue(Files.isDirectory(RECONSTITUTED_DIR),
                "Reconstituted directory not found — run ReconstitutionExportTest first");

        int total = 0, labelled = 0, unknown = 0;

        List<Path> jsonFiles;
        try (Stream<Path> walk = Files.walk(RECONSTITUTED_DIR)) {
            jsonFiles = walk
                    .filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".label.json"))
                    .toList();
        }

        for (var jsonPath : jsonFiles) {
            total++;
            try {
                var gameJson = (Map<String, Object>) MAPPER.readValue(jsonPath.toFile(), Map.class);
                var trackerEvents = (List<Map<String, Object>>) gameJson.get("trackerEvents");
                if (trackerEvents == null) continue;

                var toonMap = (Map<String, Map<String, Object>>) gameJson.get("ToonPlayerDescMap");
                String matchup = detectMatchup(toonMap);

                var counts3min = countEnemyUnitsAtTime(trackerEvents, 3.0);
                var counts5min = countEnemyUnitsAtTime(trackerEvents, 5.0);

                StrategyArchetype archetype3 = deriveArchetype(counts3min, 3.0);
                StrategyArchetype archetype5 = deriveArchetype(counts5min, 5.0);
                StrategyArchetype best = archetype3 != null ? archetype3 : archetype5;

                var sidecar = new LinkedHashMap<String, Object>();
                sidecar.put("replayHash", jsonPath.getFileName().toString().replace(".json", ""));

                if (best != null) {
                    String coarse = CATEGORY_TO_COARSE.getOrDefault(best.category(), "UNKNOWN");
                    sidecar.put("coarseLabel", coarse);
                    sidecar.put("fineGrainedLabel", best.name());
                    sidecar.put("matchup", matchup);
                    sidecar.put("confidence", 0.8);

                    var timestamps = new LinkedHashMap<String, Object>();
                    if (archetype3 != null) {
                        timestamps.put("3min", Map.of("archetype", archetype3.name(), "confidence", 0.8));
                    }
                    if (archetype5 != null) {
                        timestamps.put("5min", Map.of("archetype", archetype5.name(), "confidence", 0.8));
                    }
                    sidecar.put("timestamps", timestamps);
                    labelled++;
                } else {
                    sidecar.put("coarseLabel", "UNKNOWN");
                    sidecar.put("fineGrainedLabel", null);
                    sidecar.put("matchup", matchup);
                    sidecar.put("confidence", 0.0);
                    unknown++;
                }

                var labelPath = Path.of(jsonPath.toString().replace(".json", ".label.json"));
                MAPPER.writeValue(labelPath.toFile(), sidecar);
            } catch (Exception e) {
                System.err.printf("FAIL: %s — %s%n", jsonPath.getFileName(), e.getMessage());
            }

            if (total % 10000 == 0) {
                System.out.printf("Progress: %d processed, %d labelled, %d unknown%n", total, labelled, unknown);
            }
        }

        System.out.printf("%nLabelling complete: %d total, %d labelled, %d unknown%n", total, labelled, unknown);
        assertTrue(total > 0, "No reconstituted files found");
    }

    private static String detectMatchup(Map<String, Map<String, Object>> toonMap) {
        if (toonMap == null || toonMap.size() < 2) return "unknown";
        var players = toonMap.values().iterator();
        players.next();
        String r2 = raceShortToFull((String) players.next().getOrDefault("race", ""));
        if ("Terran".equals(r2)) return "vs_terran";
        if ("Zerg".equals(r2)) return "vs_zerg";
        if ("Protoss".equals(r2)) return "vs_protoss";
        return "unknown";
    }

    private static String raceShortToFull(String race) {
        return switch (race) {
            case "Terr" -> "Terran";
            case "Prot" -> "Protoss";
            case "Zerg" -> "Zerg";
            default -> race;
        };
    }

    private static Map<UnitType, Long> countEnemyUnitsAtTime(List<Map<String, Object>> events, double targetMinutes) {
        long targetLoop = (long) (targetMinutes * 60 * LOOPS_PER_SECOND);
        var counts = new EnumMap<UnitType, Long>(UnitType.class);

        for (var event : events) {
            String evtType = (String) event.get("_event");
            if (!"NNet.Replay.Tracker.SUnitBornEvent".equals(evtType)) continue;

            int loop = event.containsKey("_gameloop") ? ((Number) event.get("_gameloop")).intValue() : 0;
            if (loop > targetLoop) break;

            int playerId = event.containsKey("controlPlayerId")
                    ? ((Number) event.get("controlPlayerId")).intValue() : 0;
            if (playerId != 2) continue;

            String unitName = (String) event.get("unitTypeName");
            if (unitName == null) continue;

            UnitType type = TRACKER_NAME_TO_UNIT.get(unitName);
            if (type != null) {
                counts.merge(type, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static StrategyArchetype deriveArchetype(Map<UnitType, Long> counts, double gameTimeMin) {
        long marines = counts.getOrDefault(UnitType.MARINE, 0L);
        long roaches = counts.getOrDefault(UnitType.ROACH, 0L);
        long zerglings = counts.getOrDefault(UnitType.ZERGLING, 0L);
        long stalkers = counts.getOrDefault(UnitType.STALKER, 0L);
        long zealots = counts.getOrDefault(UnitType.ZEALOT, 0L);
        long siegeTanks = counts.getOrDefault(UnitType.SIEGE_TANK, 0L);
        long banshees = counts.getOrDefault(UnitType.BANSHEE, 0L);
        long hydralisks = counts.getOrDefault(UnitType.HYDRALISK, 0L);
        long mutalisks = counts.getOrDefault(UnitType.MUTALISK, 0L);
        long broodLords = counts.getOrDefault(UnitType.BROOD_LORD, 0L);
        long hellions = counts.getOrDefault(UnitType.HELLION, 0L);
        long thors = counts.getOrDefault(UnitType.THOR, 0L);
        long bcs = counts.getOrDefault(UnitType.BATTLECRUISER, 0L);
        long colossus = counts.getOrDefault(UnitType.COLOSSUS, 0L);
        long archons = counts.getOrDefault(UnitType.ARCHON, 0L);
        long carriers = counts.getOrDefault(UnitType.CARRIER, 0L);
        long dts = counts.getOrDefault(UnitType.DARK_TEMPLAR, 0L);

        if (marines >= 8 && gameTimeMin < 3.0) return StrategyArchetype.TERRAN_MARINE_RUSH;
        if (banshees >= 1 && gameTimeMin < 8.0) return StrategyArchetype.TERRAN_BANSHEE_HARASS;
        if (dts >= 1 && gameTimeMin < 8.0) return StrategyArchetype.PROTOSS_DT_HARASS;
        if (zerglings >= 6 && gameTimeMin < 4.0) return StrategyArchetype.ZERG_ZERGLING_RUSH;
        if (roaches >= 4 && gameTimeMin < 5.0) return StrategyArchetype.ZERG_ROACH_RUSH;
        if (stalkers + zealots >= 4 && gameTimeMin < 5.0) return StrategyArchetype.PROTOSS_GATEWAY_RUSH;
        if (bcs >= 1 && gameTimeMin >= 10.0) return StrategyArchetype.TERRAN_BC_TRANSITION;
        if (broodLords >= 2 && gameTimeMin >= 12.0) return StrategyArchetype.ZERG_BROOD_LORD;
        if (carriers >= 2 && gameTimeMin >= 12.0) return StrategyArchetype.PROTOSS_CARRIER;
        if (marines >= 6 && siegeTanks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.TERRAN_MARINE_TANK;
        if (hellions >= 3 && thors >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.TERRAN_BATTLE_MECH;
        if (roaches >= 3 && hydralisks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.ZERG_ROACH_HYDRA;
        if (mutalisks >= 3 && gameTimeMin >= 5.0) return StrategyArchetype.ZERG_MUTALISK_HARASS;
        if (stalkers >= 3 && colossus >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.PROTOSS_STALKER_COLOSSUS;
        if (zealots >= 4 && archons >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.PROTOSS_CHARGELOT_ARCHON;
        if (marines >= 6 && gameTimeMin >= 4.0) return StrategyArchetype.TERRAN_BIO_TIMING;
        if (siegeTanks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.TERRAN_MECH_PUSH;
        return null;
    }
}
