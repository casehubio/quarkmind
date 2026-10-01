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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class BuildingCoverageDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/input");

    private static final Set<String> BUILDING_TYPES = Set.of(
        "Nexus", "Pylon", "Gateway", "CyberneticsCore", "Assimilator",
        "RoboticsFacility", "Stargate", "Forge", "TwilightCouncil",
        "PhotonCannon", "ShieldBattery", "DarkShrine", "TemplarArchive",
        "FleetBeacon", "RoboticsBay",
        "CommandCenter", "OrbitalCommand", "PlanetaryFortress",
        "SupplyDepot", "Barracks", "EngineeringBay", "Armory",
        "MissileTurret", "Bunker", "SensorTower", "GhostAcademy",
        "Factory", "Starport", "FusionCore", "Refinery",
        "Hatchery", "SpawningPool", "EvolutionChamber", "RoachWarren",
        "BanelingNest", "SpineCrawler", "SporeCrawler", "HydraliskDen",
        "LurkerDenMP", "InfestationPit", "Spire", "NydusNetwork",
        "UltraliskCavern", "Extractor",
        "CreepTumor", "CreepTumorQueen", "NydusCanal",
        "OracleStasisTrap", "AssimilatorRich",
        "BarracksReactor", "BarracksTechLab",
        "FactoryReactor", "FactoryTechLab",
        "StarportReactor", "StarportTechLab"
    );

    static boolean oracleAndStrippedExist() {
        if (!Files.isDirectory(ORACLE_DIR) || !Files.isDirectory(STRIPPED_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleAndStrippedExist")
    @SuppressWarnings("unchecked")
    void compareBuildingCoverageAgainstOracle() throws Exception {
        Map<String, int[]> oracleCounts = new TreeMap<>();
        Map<String, int[]> javaCounts = new TreeMap<>();
        var extractor = new StrippedReplayFeatureExtractor();
        int replayCount = 0;

        try (var replays = Files.list(ORACLE_DIR)) {
            for (Path oraclePath : replays
                    .filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {

                Path strippedPath = STRIPPED_DIR.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) continue;
                replayCount++;

                Replay oracle = RepParserEngine.parseReplay(oraclePath,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracle == null || oracle.trackerEvents == null) continue;

                for (Event raw : oracle.trackerEvents.getEvents()) {
                    if (raw.getId() == ITrackerEvents.ID_UNIT_INIT) {
                        IBaseUnitEvent ui = (IBaseUnitEvent) raw;
                        String name = ui.getUnitTypeName() != null
                            ? ui.getUnitTypeName().toString() : null;
                        if (name != null && BUILDING_TYPES.contains(name)) {
                            oracleCounts.computeIfAbsent(name, k -> new int[1])[0]++;
                        }
                    }
                }

                try {
                    Map<String, Object> result = extractor.extract(strippedPath);
                    List<Map<String, Object>> events =
                        (List<Map<String, Object>>) result.get("trackerEvents");
                    if (events == null) continue;
                    for (Map<String, Object> event : events) {
                        if ("UnitInit".equals(event.get("evtTypeName"))) {
                            String name = (String) event.get("unitTypeName");
                            if (name != null && BUILDING_TYPES.contains(name)) {
                                javaCounts.computeIfAbsent(name, k -> new int[1])[0]++;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("Failed: %s — %s%n", strippedPath.getFileName(), e.getMessage());
                }
            }
        }

        System.out.printf("%n=== Building UnitInit Coverage: Oracle vs Java (%d replays) ===%n%n",
            replayCount);
        System.out.printf("%-25s %8s %8s %8s%n", "BuildingType", "Oracle", "Java", "Coverage");
        System.out.println("-".repeat(55));

        Set<String> allTypes = new java.util.TreeSet<>(oracleCounts.keySet());
        allTypes.addAll(javaCounts.keySet());
        for (String type : allTypes) {
            int oracle = oracleCounts.containsKey(type) ? oracleCounts.get(type)[0] : 0;
            int java = javaCounts.containsKey(type) ? javaCounts.get(type)[0] : 0;
            String coverage = oracle > 0
                ? String.format("%.0f%%", 100.0 * java / oracle)
                : "N/A";
            String marker = oracle > 0 && java * 100 / oracle < 90 ? " <<<" : "";
            System.out.printf("%-25s %8d %8d %8s%s%n", type, oracle, java, coverage, marker);
        }
    }
}
