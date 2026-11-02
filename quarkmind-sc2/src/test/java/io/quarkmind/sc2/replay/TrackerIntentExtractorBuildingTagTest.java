package io.quarkmind.sc2.replay;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrackerIntentExtractorBuildingTagTest {

    @Test
    void typeAwareLookupPrefersCorrectBuildingType() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0", "r-2-0", "r-3-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.FACTORY,
            "r-2-0", BuildingType.BARRACKS,
            "r-3-0", BuildingType.STARPORT
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.MARINE);
        assertEquals("r-2-0", result, "Should pick Barracks for Marine");

        result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.SIEGE_TANK);
        assertEquals("r-1-0", result, "Should pick Factory for SiegeTank");

        result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.MEDIVAC);
        assertEquals("r-3-0", result, "Should pick Starport for Medivac");
    }

    @Test
    void returnsNullWhenNoCorrectBuildingTypeInSelection() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.FACTORY
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.MARINE);
        assertNull(result, "Should return null when no Barracks in selection");
    }

    @Test
    void returnsFirstTagWhenBuildingTypeIsUnknown() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0", "r-2-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.FACTORY,
            "r-2-0", BuildingType.BARRACKS
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.UNKNOWN);
        assertEquals("r-1-0", result,
            "UNKNOWN trainedBy returns UNKNOWN building type — falls back to first tag");
    }

    @Test
    void windowMatchingStillApplies() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.BARRACKS
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 103, UnitType.MARINE);
        assertEquals("r-1-0", result, "Should match within ±5 loop window");

        result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 120, UnitType.MARINE);
        assertNull(result, "Should not match outside window");
    }

    @Test
    void canTrainFromHandlesUpgradedBuildings() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.ORBITAL_COMMAND
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.SCV);
        assertEquals("r-1-0", result,
            "Orbital Command should match for SCV (upgrades from Command Center)");
    }

    @Test
    void handlesUnknownTagsInSelection() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-99-0", "r-2-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-2-0", BuildingType.BARRACKS
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.MARINE);
        assertEquals("r-2-0", result,
            "Should skip tags not in the type map and find the correct one");
    }

    @Test
    void zergTrainFromHatcheryVariants() {
        TreeMap<Integer, List<String>> index = new TreeMap<>();
        index.put(100, List.of("r-1-0"));

        Map<String, BuildingType> tagToType = Map.of(
            "r-1-0", BuildingType.LAIR
        );

        String result = TrackerIntentExtractor.lookupBuildingTag(
            index, tagToType, 100, UnitType.ZERGLING);
        assertEquals("r-1-0", result,
            "Lair should match for Zergling (upgrades from Hatchery)");
    }
}
