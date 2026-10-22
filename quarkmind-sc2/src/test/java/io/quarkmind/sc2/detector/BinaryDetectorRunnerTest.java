package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.plugin.scouting.FeatureIndexMaps;
import io.quarkmind.plugin.scouting.StrategyFeatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BinaryDetectorRunnerTest {

    private static final Path MODELS_DIR = Path.of("src/main/resources/models/detectors");
    private static BinaryDetectorRunner runner;

    @BeforeAll
    static void setUp() {
        assumeTrue(Files.isDirectory(MODELS_DIR) && MODELS_DIR.toFile().listFiles((d, n) -> n.endsWith(".onnx")) != null && MODELS_DIR.toFile().listFiles((d, n) -> n.endsWith(".onnx")).length > 0, "ONNX model files not present");
        runner = new BinaryDetectorRunner(MODELS_DIR);
    }

    @AfterAll
    static void tearDown() {
        if (runner != null) runner.close();
    }

    @Test
    void adaptFeatures_producesCorrectDimensions() {
        int javaFPW = FeatureIndexMaps.FEATURES_PER_WINDOW;
        float[] javaFlat = new float[10 * javaFPW];
        float[] javaMap = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};

        Map<String, float[][]> adapted = BinaryDetectorRunner.adaptFeatures(
            javaFlat, javaMap);

        assertThat(adapted.get("temporal")[0]).hasSize(4090);
        assertThat(adapted.get("map")[0]).hasSize(4);
    }

    @Test
    void adaptFeatures_preservesPlayerPrefix() {
        int javaFPW = FeatureIndexMaps.FEATURES_PER_WINDOW;
        float[] javaFlat = new float[10 * javaFPW];
        javaFlat[3] = 2.0f;
        javaFlat[54] = 5.0f;
        float[] javaMap = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};

        Map<String, float[][]> adapted = BinaryDetectorRunner.adaptFeatures(
            javaFlat, javaMap);

        float[] temporal = adapted.get("temporal")[0];
        assertThat(temporal[3]).isEqualTo(2.0f);
        assertThat(temporal[54]).isEqualTo(5.0f);
    }

    @Test
    void adaptFeatures_copiesHasVision() {
        int javaFPW = FeatureIndexMaps.FEATURES_PER_WINDOW;
        float[] javaFlat = new float[10 * javaFPW];
        javaFlat[FeatureIndexMaps.HAS_VISION_INDEX] = 1.0f;
        float[] javaMap = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};

        Map<String, float[][]> adapted = BinaryDetectorRunner.adaptFeatures(
            javaFlat, javaMap);

        float[] temporal = adapted.get("temporal")[0];
        int onnxFPW = BinaryDetectorRunner.ONNX_FEATURES_PER_WINDOW;
        assertThat(temporal[onnxFPW - 1]).isEqualTo(1.0f);
    }

    @Test
    void adaptFeatures_dropsMapAvailabilityFlags() {
        float[] javaFlat = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
        float[] javaMap = new float[]{0.3f, 0.4f, 0.5f, 0.6f, 1.0f, 0.0f};

        Map<String, float[][]> adapted = BinaryDetectorRunner.adaptFeatures(
            javaFlat, javaMap);

        float[] map = adapted.get("map")[0];
        assertThat(map).containsExactly(0.3f, 0.4f, 0.5f, 0.6f);
    }

    @Test
    void runActiveDetectors_earlyPhaseVsTerran_returnsDetectorOutputs() {
        var features = buildTestFeatures();
        Map<String, Float> outputs = runner.runActiveDetectors(
            GamePhase.EARLY, Race.TERRAN, features);

        assertThat(outputs).containsKeys("early_rush_vs_terran", "early_greedy_vs_terran");
        assertThat(outputs).hasSize(2);
        for (float p : outputs.values()) {
            assertThat(p).isBetween(0.0f, 1.0f);
        }
    }

    @Test
    void runActiveDetectors_earlyPhaseVsProtoss_includesCheese() {
        var features = buildTestFeatures();
        Map<String, Float> outputs = runner.runActiveDetectors(
            GamePhase.EARLY, Race.PROTOSS, features);

        assertThat(outputs).containsKeys(
            "early_rush_vs_protoss", "early_greedy_vs_protoss", "early_cheese_vs_protoss");
        assertThat(outputs).hasSize(3);
    }

    @Test
    void runActiveDetectors_latePhaseVsZerg_excludesAirSuperiority() {
        var features = buildTestFeatures();
        Map<String, Float> outputs = runner.runActiveDetectors(
            GamePhase.LATE, Race.ZERG, features);

        assertThat(outputs).containsKey("late_ground_army_vs_zerg");
        assertThat(outputs).doesNotContainKey("late_air_superiority_vs_zerg");
        assertThat(outputs).hasSize(1);
    }

    private StrategyFeatures buildTestFeatures() {
        int javaFPW = FeatureIndexMaps.FEATURES_PER_WINDOW;
        float[] temporal = new float[10 * javaFPW];
        float[] mapFeatures = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};
        return new StrategyFeatures(Map.of(
            "temporal", new float[][]{temporal},
            "map", new float[][]{mapFeatures}));
    }
}
