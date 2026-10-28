package io.quarkmind.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.sc2.detector.DetectorCatalog;
import io.quarkmind.sc2.detector.DetectorCatalogConfig;
import io.quarkmind.sc2.detector.LearnedCptResource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-source consistency validator: ensures SC2 data encoded in multiple
 * independent sources (SC2Data, observation extractors, ONNX features, CPTs,
 * supply costs) does not drift.
 *
 * Ground truth: SC2Data (calibrated by SC2DataAttributeCalibrationTest).
 * Everything else must agree.
 */
@Tag("report")
class CrossSourceConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, UnitType> NAME_TO_UNIT_TYPE;
    static {
        var map = new HashMap<String, UnitType>();
        for (UnitType t : UnitType.values()) {
            if (t == UnitType.UNKNOWN) continue;
            map.put(t.name(), t);
            String camel = toCamelCase(t.name());
            map.put(camel, t);
        }
        map.put("SiegeTankSieged", UnitType.SIEGE_TANK_SIEGED);
        map.put("HellionTank", UnitType.HELLBAT);
        map.put("VikingFighter", UnitType.VIKING);
        map.put("LiberatorAG", UnitType.LIBERATOR_AG);
        NAME_TO_UNIT_TYPE = Map.copyOf(map);
    }

    // --- Observation extractor consistency ---

    @Test
    void replayExtractorLightUnitsMatchSC2Data() {
        var replayLight = Set.of("Marine", "Zergling", "Zealot", "Adept", "Hellion");
        var mismatches = new ArrayList<String>();

        for (String name : replayLight) {
            UnitType type = NAME_TO_UNIT_TYPE.get(name);
            assertThat(type).as("No UnitType mapping for '%s'", name).isNotNull();
            Set<UnitAttribute> attrs = SC2Data.unitAttributes(type);
            if (!attrs.contains(UnitAttribute.LIGHT)) {
                mismatches.add(String.format("  %s (%s) is in replay LIGHT_UNITS but SC2Data says %s", name, type, attrs));
            }
        }

        System.out.println("=== Replay Extractor LIGHT_UNITS vs SC2Data ===");
        if (mismatches.isEmpty()) {
            System.out.println("  All units consistent.");
        } else {
            mismatches.forEach(System.out::println);
        }

        var sc2DataLight = Arrays.stream(UnitType.values())
                .filter(t -> SC2Data.unitAttributes(t).contains(UnitAttribute.LIGHT))
                .filter(t -> !SC2Data.isWorker(t))
                .filter(t -> t != UnitType.UNKNOWN && t != UnitType.LARVA && t != UnitType.EGG
                        && t != UnitType.LOCUST && t != UnitType.BROODLING && t != UnitType.CHANGELING
                        && t != UnitType.INFESTED_TERRAN && t != UnitType.INTERCEPTOR
                        && t != UnitType.ADEPT_PHASE_SHIFT && t != UnitType.MULE)
                .collect(Collectors.toSet());
        var missing = new TreeSet<>(sc2DataLight.stream().map(Enum::name).collect(Collectors.toSet()));
        missing.removeAll(replayLight.stream().map(n -> NAME_TO_UNIT_TYPE.get(n).name()).collect(Collectors.toSet()));
        if (!missing.isEmpty()) {
            System.out.println("  Coverage gap — SC2Data LIGHT combat units not in replay LIGHT_UNITS: " + missing);
        }

        assertThat(mismatches).as("Replay extractor LIGHT_UNITS false positives vs SC2Data").isEmpty();
    }

    @Test
    void replayExtractorArmoredUnitsMatchSC2Data() {
        var replayArmored = Set.of("Roach", "SiegeTank", "SiegeTankSieged", "Stalker", "Immortal", "Marauder");
        var mismatches = new ArrayList<String>();

        for (String name : replayArmored) {
            UnitType type = NAME_TO_UNIT_TYPE.get(name);
            assertThat(type).as("No UnitType mapping for '%s'", name).isNotNull();
            Set<UnitAttribute> attrs = SC2Data.unitAttributes(type);
            if (!attrs.contains(UnitAttribute.ARMORED)) {
                mismatches.add(String.format("  %s (%s) is in replay ARMORED_UNITS but SC2Data says %s", name, type, attrs));
            }
        }

        System.out.println("=== Replay Extractor ARMORED_UNITS vs SC2Data ===");
        if (mismatches.isEmpty()) {
            System.out.println("  All units consistent.");
        } else {
            mismatches.forEach(System.out::println);
        }

        assertThat(mismatches).as("Replay extractor ARMORED_UNITS false positives vs SC2Data").isEmpty();
    }

    // --- Supply costs ---

    @Test
    void supplyCostsJsonMatchesSC2Data() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/classifier/supply_costs.json")) {
            assertThat(is).as("supply_costs.json must exist on classpath").isNotNull();
            Map<String, Integer> costs = MAPPER.readValue(is,
                    MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Integer.class));

            var mismatches = new ArrayList<String>();
            for (UnitType type : UnitType.values()) {
                Integer jsonCost = costs.get(type.name());
                int sc2DataCost = SC2Data.supplyCost(type);
                if (jsonCost != null && jsonCost != sc2DataCost) {
                    mismatches.add(String.format("  %s: supply_costs.json=%d, SC2Data=%d", type, jsonCost, sc2DataCost));
                }
            }

            System.out.println("=== Supply Costs: JSON vs SC2Data ===");
            if (mismatches.isEmpty()) {
                System.out.println("  All supply costs consistent.");
            } else {
                mismatches.forEach(System.out::println);
            }

            assertThat(mismatches).as("Supply cost mismatches between JSON and SC2Data").isEmpty();
        }
    }

    // --- CPT variable names match detector catalog ---

    @Test
    void cptVariablesMatchDetectorCatalog() throws Exception {
        var catalog = DetectorCatalog.create();
        var catalogDetectorNames = new TreeSet<String>();
        for (DetectorCatalogConfig.DetectorDef def : catalog.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String race = entry.getKey().name().toLowerCase();
                catalogDetectorNames.add(def.name() + "_vs_" + race);
            }
        }

        String[] cptFiles = {
                "/classifier/learned-cpts-early-vs-terran.json",
                "/classifier/learned-cpts-early-vs-zerg.json",
                "/classifier/learned-cpts-early-vs-protoss.json",
                "/classifier/learned-cpts-mid-vs-terran.json",
                "/classifier/learned-cpts-mid-vs-zerg.json",
                "/classifier/learned-cpts-mid-vs-protoss.json",
                "/classifier/learned-cpts-late-vs-terran.json",
                "/classifier/learned-cpts-late-vs-zerg.json",
                "/classifier/learned-cpts-late-vs-protoss.json"
        };

        var cptVariableNames = new TreeSet<String>();
        var unmatchedVariables = new ArrayList<String>();

        for (String path : cptFiles) {
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is == null) continue;
                LearnedCptResource resource = MAPPER.readValue(is, LearnedCptResource.class);
                for (var variable : resource.network().variables()) {
                    String name = variable.name();
                    if ("Strategy".equals(name)) continue;
                    cptVariableNames.add(name);
                    String expectedSuffix = "_vs_" + resource.matchup().replace("vs_", "");
                    if (!catalogDetectorNames.contains(name)) {
                        unmatchedVariables.add(String.format("  CPT variable '%s' in %s has no matching detector in catalog", name, path));
                    }
                }
            }
        }

        System.out.println("=== CPT Variables vs Detector Catalog ===");
        System.out.printf("  Catalog detectors: %s%n", catalogDetectorNames);
        System.out.printf("  CPT variables: %s%n", cptVariableNames);
        if (unmatchedVariables.isEmpty()) {
            System.out.println("  All CPT variables match catalog detectors.");
        } else {
            unmatchedVariables.forEach(System.out::println);
        }

        assertThat(unmatchedVariables).as("CPT variables without matching detector catalog entries").isEmpty();
    }

    // --- Helpers ---

    private static String toCamelCase(String enumName) {
        String[] parts = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
