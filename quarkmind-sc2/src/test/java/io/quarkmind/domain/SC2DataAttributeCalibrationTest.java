package io.quarkmind.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static io.quarkmind.domain.UnitAttribute.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration test: validates SC2Data.unitAttributes() against SC2 game data ground truth
 * (patch 5.0.16, sourced from starcraft2.ai/en/ai-coach/units).
 *
 * Summoned/temporary units (Interceptor, Locust, Broodling, etc.) are calibrated from
 * known SC2 game data where the reference site omits them.
 */
@Tag("report")
class SC2DataAttributeCalibrationTest {

    private static final Map<UnitType, Set<UnitAttribute>> GROUND_TRUTH;

    static {
        var map = new EnumMap<UnitType, Set<UnitAttribute>>(UnitType.class);

        // === Protoss ===
        map.put(UnitType.PROBE,              Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.ZEALOT,             Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.STALKER,            Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.SENTRY,             Set.of(MECHANICAL, PSIONIC));
        map.put(UnitType.ADEPT,              Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.HIGH_TEMPLAR,       Set.of(LIGHT, BIOLOGICAL, PSIONIC));
        map.put(UnitType.DARK_TEMPLAR,       Set.of(LIGHT, BIOLOGICAL, PSIONIC));
        map.put(UnitType.ARCHON,             Set.of(PSIONIC, MASSIVE));
        map.put(UnitType.IMMORTAL,           Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.COLOSSUS,           Set.of(ARMORED, MECHANICAL, MASSIVE));
        map.put(UnitType.DISRUPTOR,          Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.OBSERVER,           Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.WARP_PRISM,         Set.of(ARMORED, MECHANICAL, PSIONIC));
        map.put(UnitType.WARP_PRISM_PHASING, Set.of(ARMORED, MECHANICAL, PSIONIC));
        map.put(UnitType.PHOENIX,            Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.VOID_RAY,           Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.ORACLE,             Set.of(ARMORED, MECHANICAL, PSIONIC));
        map.put(UnitType.TEMPEST,            Set.of(ARMORED, MECHANICAL, MASSIVE));
        map.put(UnitType.CARRIER,            Set.of(ARMORED, MECHANICAL, MASSIVE));
        map.put(UnitType.INTERCEPTOR,        Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.MOTHERSHIP,         Set.of(ARMORED, MECHANICAL, MASSIVE, PSIONIC));
        map.put(UnitType.ADEPT_PHASE_SHIFT,  Set.of(LIGHT));

        // === Zerg ===
        map.put(UnitType.LARVA,              Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.DRONE,              Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.ZERGLING,           Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.BANELING,           Set.of(BIOLOGICAL));
        map.put(UnitType.ROACH,              Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.RAVAGER,            Set.of(BIOLOGICAL));
        map.put(UnitType.HYDRALISK,          Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.LURKER,             Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.INFESTOR,           Set.of(ARMORED, BIOLOGICAL, PSIONIC));
        map.put(UnitType.SWARM_HOST,         Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.MUTALISK,           Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.CORRUPTOR,          Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.BROOD_LORD,         Set.of(ARMORED, BIOLOGICAL, MASSIVE));
        map.put(UnitType.VIPER,              Set.of(ARMORED, BIOLOGICAL, PSIONIC));
        map.put(UnitType.ULTRALISK,          Set.of(ARMORED, BIOLOGICAL, MASSIVE));
        map.put(UnitType.OVERLORD,           Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.OVERSEER,           Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.QUEEN,              Set.of(BIOLOGICAL, PSIONIC));
        map.put(UnitType.LOCUST,             Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.BROODLING,          Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.INFESTED_TERRAN,    Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.CHANGELING,         Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.EGG,                Set.of(BIOLOGICAL));

        // === Terran ===
        map.put(UnitType.SCV,                Set.of(LIGHT, BIOLOGICAL, MECHANICAL));
        map.put(UnitType.MARINE,             Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.MARAUDER,           Set.of(ARMORED, BIOLOGICAL));
        map.put(UnitType.REAPER,             Set.of(LIGHT, BIOLOGICAL));
        map.put(UnitType.GHOST,              Set.of(LIGHT, BIOLOGICAL, PSIONIC));
        map.put(UnitType.HELLION,            Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.HELLBAT,            Set.of(LIGHT, BIOLOGICAL, MECHANICAL));
        map.put(UnitType.WIDOW_MINE,         Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.SIEGE_TANK,         Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.SIEGE_TANK_SIEGED,  Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.CYCLONE,            Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.THOR,               Set.of(ARMORED, MECHANICAL, MASSIVE));
        map.put(UnitType.VIKING,             Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.VIKING_ASSAULT,     Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.MEDIVAC,            Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.LIBERATOR,          Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.LIBERATOR_AG,       Set.of(ARMORED, MECHANICAL));
        map.put(UnitType.RAVEN,              Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.BANSHEE,            Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.BATTLECRUISER,      Set.of(ARMORED, MECHANICAL, MASSIVE));
        map.put(UnitType.MULE,               Set.of(LIGHT, MECHANICAL));
        map.put(UnitType.AUTO_TURRET,        Set.of(ARMORED, MECHANICAL, STRUCTURE));

        // Fallback
        map.put(UnitType.UNKNOWN,            Set.of());

        for (UnitType t : UnitType.values()) {
            if (!map.containsKey(t)) {
                throw new ExceptionInInitializerError("GROUND_TRUTH missing entry for " + t);
            }
        }
        GROUND_TRUTH = Collections.unmodifiableMap(map);
    }

    @Test
    void unitAttributesMatchGroundTruth() {
        var mismatches = new ArrayList<String>();

        System.out.println("=== SC2Data Attribute Calibration ===");
        System.out.printf("  %-22s  %-35s  %-35s  %s%n", "Unit", "SC2Data", "Ground Truth", "Status");
        System.out.printf("  %-22s  %-35s  %-35s  %s%n", "----", "------", "------------", "------");

        for (UnitType type : UnitType.values()) {
            Set<UnitAttribute> actual = SC2Data.unitAttributes(type);
            Set<UnitAttribute> expected = GROUND_TRUTH.get(type);

            var missing = EnumSet.noneOf(UnitAttribute.class);
            missing.addAll(expected);
            missing.removeAll(actual);

            var extra = EnumSet.noneOf(UnitAttribute.class);
            extra.addAll(actual);
            extra.removeAll(expected);

            String status;
            if (missing.isEmpty() && extra.isEmpty()) {
                status = "OK";
            } else {
                var parts = new ArrayList<String>();
                if (!missing.isEmpty()) parts.add("missing=" + missing);
                if (!extra.isEmpty()) parts.add("extra=" + extra);
                status = "MISMATCH " + String.join(", ", parts);
                mismatches.add(String.format("  %-22s  %s", type, status));
            }

            System.out.printf("  %-22s  %-35s  %-35s  %s%n",
                    type, sorted(actual), sorted(expected), status);
        }

        if (!mismatches.isEmpty()) {
            System.out.println("\n=== Mismatches ===");
            mismatches.forEach(System.out::println);

            System.out.println("\n=== Corrected SC2Data entries ===");
            for (UnitType type : UnitType.values()) {
                Set<UnitAttribute> expected = GROUND_TRUTH.get(type);
                Set<UnitAttribute> actual = SC2Data.unitAttributes(type);
                if (!actual.equals(expected)) {
                    String attrs = expected.isEmpty() ? "Set.of()"
                            : "Set.of(" + String.join(", ",
                            sorted(expected).stream().map(Enum::name).toList()) + ")";
                    System.out.printf("  map.put(UnitType.%-22s %s);%n", type + ",", attrs);
                }
            }
        }

        assertThat(mismatches)
                .as("SC2Data.unitAttributes() mismatches against ground truth (patch 5.0.16)")
                .isEmpty();
    }

    private static List<UnitAttribute> sorted(Set<UnitAttribute> attrs) {
        var list = new ArrayList<>(attrs);
        list.sort(Comparator.comparingInt(Enum::ordinal));
        return list;
    }
}
