package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Tag("report")
class CalibrationFactorDerivationTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");
    private static final Path OUTPUT_PATH = Path.of(
            "src/main/resources/calibration/multiplication-factors.json");

    private static final Set<String> COMBAT_SPAWN_UNITS = Set.of(
            "Broodling", "BroodlingEscort", "Locust", "InfestorTerran",
            "Changeling", "ChangelingMarine", "ChangelingMarineShield",
            "ChangelingZealot", "ChangelingZergling", "ChangelingZerglingWings",
            "ParasiticBombDummy", "KD8Charge", "AutoTurret", "PointDefenseDrone");

    private static final Set<String> AUTO_SPAWN_UNITS = Set.of("Larva", "Interceptor");

    private static final Set<String> BUILDING_NAMES;

    static {
        var names = new HashSet<String>();
        for (var bt : io.quarkmind.domain.BuildingType.values()) {
            String n = StrippedReplayFeatureExtractor.buildingTypeToPythonName(bt);
            if (n != null) names.add(n);
        }
        names.add("WarpGate");
        names.add("SupplyDepotLowered");
        names.add("RefineryRich");
        names.add("ExtractorRich");
        names.add("BarracksFlying");
        names.add("FactoryFlying");
        names.add("StarportFlying");
        names.add("CommandCenterFlying");
        names.add("OrbitalCommandFlying");
        BUILDING_NAMES = Set.copyOf(names);
    }

    static boolean datasetsExist() {
        return Files.isDirectory(ORACLE_DIR) && Files.isDirectory(STRIPPED_DIR);
    }

    @Test
    @EnabledIf("datasetsExist")
    void deriveCalibrationFactors() throws Exception {
        Map<String, Long> oracleTotals = new TreeMap<>();
        Map<String, Long> uncalibratedTotals = new TreeMap<>();
        int replayCount = 0;

        var extractor = new StrippedReplayFeatureExtractor();

        List<Path> oracleReplays;
        try (var stream = Files.list(ORACLE_DIR)) {
            oracleReplays = stream
                    .filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        for (Path oracleReplay : oracleReplays) {
            Path stripped = STRIPPED_DIR.resolve(oracleReplay.getFileName());
            if (!Files.exists(stripped)) continue;

            Replay oracleRep = RepParserEngine.parseReplay(oracleReplay,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
            if (oracleRep == null || oracleRep.trackerEvents == null) continue;

            Map<String, Long> oracleCounts = new TreeMap<>();
            for (Event evt : oracleRep.trackerEvents.getEvents()) {
                if (evt.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
                IBaseUnitEvent born = (IBaseUnitEvent) evt;
                if (born.getControlPlayerId() == null || born.getControlPlayerId() == 0) continue;
                if (born.getLoop() == 0) continue;
                String name = born.getUnitTypeName().toString();
                if (COMBAT_SPAWN_UNITS.contains(name)) continue;
                if (AUTO_SPAWN_UNITS.contains(name)) continue;
                if (BUILDING_NAMES.contains(name)) continue;
                oracleCounts.merge(name, 1L, Long::sum);
            }

            Map<String, Object> javaJson;
            try {
                javaJson = extractor.extract(stripped);
            } catch (Exception e) {
                continue;
            }

            Map<String, Long> javaCounts = new TreeMap<>();
            @SuppressWarnings("unchecked")
            var events = (List<Map<String, Object>>) javaJson.get("trackerEvents");
            if (events != null) {
                for (var event : events) {
                    if (!"UnitBorn".equals(event.get("evtTypeName"))) continue;
                    Object pidObj = event.get("controlPlayerId");
                    if (pidObj == null) continue;
                    int pid = ((Number) pidObj).intValue();
                    if (pid == 0) continue;
                    String name = (String) event.get("unitTypeName");
                    if (name == null) continue;
                    if (COMBAT_SPAWN_UNITS.contains(name)) continue;
                    if (AUTO_SPAWN_UNITS.contains(name)) continue;
                    if (BUILDING_NAMES.contains(name)) continue;
                    javaCounts.merge(name, 1L, Long::sum);
                }
            }

            oracleCounts.forEach((k, v) -> oracleTotals.merge(k, v, Long::sum));
            javaCounts.forEach((k, v) -> uncalibratedTotals.merge(k, v, Long::sum));
            replayCount++;
        }

        Map<String, Double> factors = new TreeMap<>();
        Set<String> allTypes = new TreeSet<>();
        allTypes.addAll(oracleTotals.keySet());
        allTypes.addAll(uncalibratedTotals.keySet());

        System.out.printf("%nCalibration factors derived from %d replays (full game):%n%n", replayCount);
        System.out.printf("%-25s %8s %12s %8s%n", "Type", "Oracle", "Uncalibrated", "Factor");
        System.out.println("-".repeat(60));
        for (String type : allTypes) {
            long oracle = oracleTotals.getOrDefault(type, 0L);
            long uncalibrated = uncalibratedTotals.getOrDefault(type, 0L);
            if (uncalibrated == 0 || oracle == 0) {
                System.out.printf("%-25s %8d %12d %8s%n", type, oracle, uncalibrated, "N/A");
                continue;
            }
            double factor = (double) oracle / uncalibrated;
            factors.put(type, Math.round(factor * 100.0) / 100.0);
            System.out.printf("%-25s %8d %12d %8.2f%n", type, oracle, uncalibrated, factor);
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        var output = new LinkedHashMap<String, Object>();
        output.put("version", "oracle-v4.9.3-" + replayCount);
        output.put("dataset", "oracle");
        output.put("replay_count", replayCount);
        output.put("factors", factors);

        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(OUTPUT_PATH.toFile(), output);
        System.out.printf("%nWrote %d factors to %s%n", factors.size(), OUTPUT_PATH);
    }
}
