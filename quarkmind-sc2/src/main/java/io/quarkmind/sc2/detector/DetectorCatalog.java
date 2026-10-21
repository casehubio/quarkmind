package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;

import java.util.List;
import java.util.Map;

public final class DetectorCatalog {

    public static DetectorCatalogConfig create() {
        return new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "early_rush", GamePhase.EARLY, FeatureTier.COMPOSITION,
                Map.of(
                    Race.PROTOSS, List.of("RUSH", "PROXY"),
                    Race.TERRAN, List.of("RUSH"),
                    Race.ZERG, List.of("RUSH", "ROACH_RUSH")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "early_cheese", GamePhase.EARLY, FeatureTier.COMPOSITION,
                Map.of(
                    Race.PROTOSS, List.of("CANNON_RUSH")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "early_greedy", GamePhase.EARLY, FeatureTier.COMPOSITION,
                Map.of(
                    Race.PROTOSS, List.of("MACRO_ECONOMY"),
                    Race.TERRAN, List.of("MACRO_ECONOMY"),
                    Race.ZERG, List.of("MACRO_ECONOMY")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "mid_air_tech", GamePhase.MID, FeatureTier.COMPOSITION,
                Map.of(
                    Race.TERRAN, List.of("BANSHEE_HARASS", "AIR_SUPERIORITY"),
                    Race.PROTOSS, List.of("AIR_SUPERIORITY"),
                    Race.ZERG, List.of("MUTA_HARASS")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "mid_hidden_tech", GamePhase.MID, FeatureTier.COMPOSITION,
                Map.of(
                    Race.PROTOSS, List.of("DT_RUSH"),
                    Race.TERRAN, List.of("TECH_RUSH")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "mid_ground_heavy", GamePhase.MID, FeatureTier.COMPOSITION,
                Map.of(
                    Race.TERRAN, List.of("MECH_PUSH"),
                    Race.PROTOSS, List.of("COLOSSUS_PUSH"),
                    Race.ZERG, List.of("HYDRA_PUSH")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "mid_timing_attack", GamePhase.MID, FeatureTier.COMPOSITION,
                Map.of(
                    Race.TERRAN, List.of("BIO_TIMING"),
                    Race.PROTOSS, List.of("BLINK_STALKER"),
                    Race.ZERG, List.of("LING_BANE")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "late_air_superiority", GamePhase.LATE, FeatureTier.COMPOSITION,
                Map.of(
                    Race.TERRAN, List.of("AIR_SUPERIORITY"),
                    Race.PROTOSS, List.of("AIR_SUPERIORITY")
                ), "models/detectors"),

            new DetectorCatalogConfig.DetectorDef(
                "late_ground_army", GamePhase.LATE, FeatureTier.COMPOSITION,
                Map.of(
                    Race.TERRAN, List.of("MECH_PUSH"),
                    Race.PROTOSS, List.of("COLOSSUS_PUSH"),
                    Race.ZERG, List.of("HYDRA_PUSH")
                ), "models/detectors")
        ));
    }

    private DetectorCatalog() {}
}
