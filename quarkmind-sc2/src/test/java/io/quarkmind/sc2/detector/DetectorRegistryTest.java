package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DetectorRegistryTest {

    @Test
    void getActiveDetectorsFiltersByPhaseAndMatchup() {
        var config = new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "early_rush", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.PROTOSS, List.of("RUSH"),
                       Race.TERRAN, List.of("RUSH")),
                "models/early_rush.onnx"),
            new DetectorCatalogConfig.DetectorDef(
                "mid_air_tech", GamePhase.MID, FeatureTier.COMPOSITION,
                Map.of(Race.TERRAN, List.of("AIR_SUPERIORITY")),
                "models/mid_air_tech.onnx")
        ));

        var registry = new DetectorRegistry(config);

        var earlyProtoss = registry.getActiveDetectors(GamePhase.EARLY, Race.PROTOSS);
        assertEquals(1, earlyProtoss.size());
        assertEquals("early_rush", earlyProtoss.get(0).name());

        var midProtoss = registry.getActiveDetectors(GamePhase.MID, Race.PROTOSS);
        assertTrue(midProtoss.isEmpty());

        var midTerran = registry.getActiveDetectors(GamePhase.MID, Race.TERRAN);
        assertEquals(1, midTerran.size());
        assertEquals("mid_air_tech", midTerran.get(0).name());
    }

    @Test
    void getFeatureTierReturnsConfiguredTier() {
        var config = new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "early_rush", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.PROTOSS, List.of("RUSH")),
                "models/early_rush.onnx")
        ));

        var registry = new DetectorRegistry(config);
        assertEquals(FeatureTier.ECONOMY, registry.getFeatureTier("early_rush"));
    }

    @Test
    void getFeatureTierThrowsForUnknown() {
        var registry = new DetectorRegistry(new DetectorCatalogConfig(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> registry.getFeatureTier("nonexistent"));
    }

    @Test
    void detectorKeyIncludesRace() {
        var def = new DetectorCatalogConfig.DetectorDef(
            "early_rush", GamePhase.EARLY, FeatureTier.ECONOMY,
            Map.of(Race.PROTOSS, List.of("RUSH")),
            "models/early_rush.onnx");
        assertEquals("early_rush_vs_protoss", def.key(Race.PROTOSS));
        assertEquals("early_rush_vs_terran", def.key(Race.TERRAN));
    }
}
