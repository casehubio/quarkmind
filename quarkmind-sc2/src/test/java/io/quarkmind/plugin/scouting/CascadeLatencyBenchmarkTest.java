package io.quarkmind.plugin.scouting;

import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.TensorClassifier;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("benchmark")
class CascadeLatencyBenchmarkTest {

    private static final Path MODELS_DIR = Path.of("src/test/resources/models/strategy");
    private static final int WARMUP = 100;
    private static final int ITERATIONS = 1000;

    private static OnnxInferenceModel terranModel;
    private static TensorClassifier terranClassifier;
    private static EnumMap<Race, TensorClassifier> onnxClassifiers;

    @BeforeAll
    static void loadModels() {
        terranModel = new OnnxInferenceModel(new ModelConfig(MODELS_DIR.resolve("strategy_vs_terran.onnx")));
        terranClassifier = new TensorClassifier(terranModel, OnnxLabelMapping.VS_TERRAN_LABELS);
        onnxClassifiers = new EnumMap<>(Race.class);
        onnxClassifiers.put(Race.TERRAN, terranClassifier);
    }

    @AfterAll
    static void closeModels() {
        if (terranModel != null) terranModel.close();
    }

    @Test
    void cascadeLatency_p99Under10ms() {
        var accumulator = new TemporalWindowAccumulator();
        populateAccumulator(accumulator, 180);
        var featureExtractor = new StrategyFeatureExtractor();
        var windowedFeatures = accumulator.getWindowedFeatures();

        List<EvidenceMarker> evidence = List.of(
                new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3, "marines-early"),
                new EvidenceMarker(StrategyArchetype.TERRAN_BIO_TIMING, 0.2, "bio-hint"));
        List<ConfidenceRevision> revisions = List.of();

        StrategyFeatures features = featureExtractor.extract(windowedFeatures, MapCharacteristics.DEFAULT);

        long[] extractionNanos = new long[ITERATIONS];
        long[] onnxNanos = new long[ITERATIONS];
        long[] cascadeNanos = new long[ITERATIONS];

        for (int i = 0; i < WARMUP; i++) {
            featureExtractor.extract(windowedFeatures, MapCharacteristics.DEFAULT);
            terranClassifier.classify(features.tensors());
            var classifier = new CascadingPatternClassifier(0.7, 0.5, onnxClassifiers);
            classifier.classify(evidence, revisions, features, Race.TERRAN, 4000, 3978, null);
        }

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            featureExtractor.extract(windowedFeatures, MapCharacteristics.DEFAULT);
            extractionNanos[i] = System.nanoTime() - start;
        }

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            terranClassifier.classify(features.tensors());
            onnxNanos[i] = System.nanoTime() - start;
        }

        for (int i = 0; i < ITERATIONS; i++) {
            var classifier = new CascadingPatternClassifier(0.7, 0.5, onnxClassifiers);
            long start = System.nanoTime();
            classifier.classify(evidence, revisions, features, Race.TERRAN, 4000, 3978, null);
            cascadeNanos[i] = System.nanoTime() - start;
        }

        Arrays.sort(extractionNanos);
        Arrays.sort(onnxNanos);
        Arrays.sort(cascadeNanos);

        StringBuilder report = new StringBuilder();
        report.append("Cascade Latency Benchmark (vs_terran model, minute 3 features):\n");
        report.append(String.format("  %-22s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Component", "Mean", "p50", "p95", "p99", "Max"));
        appendRow(report, "Feature extraction", extractionNanos);
        appendRow(report, "ONNX inference", onnxNanos);
        appendRow(report, "Full cascade", cascadeNanos);
        System.out.println(report);

        double extractionP99Ms = extractionNanos[p(99)] / 1_000_000.0;
        double cascadeP99Ms = cascadeNanos[p(99)] / 1_000_000.0;

        assertThat(extractionP99Ms).as("Feature extraction p99 < 1ms").isLessThan(1.0);
        assertThat(cascadeP99Ms).as("Full cascade p99 < 10ms").isLessThan(10.0);
    }

    private static void populateAccumulator(TemporalWindowAccumulator acc, int ticks) {
        for (int i = 0; i < ticks; i++) {
            float[] player = new float[FeatureIndexMaps.N_FEATURES_PER_PLAYER];
            float[] opponent = new float[FeatureIndexMaps.N_FEATURES_PER_PLAYER];
            player[0] = 1.0f;
            player[FeatureIndexMaps.N_BUILDINGS] = (i / 30) + 1;
            player[FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.N_UNITS] = 400.0f / 1000.0f;
            opponent[FeatureIndexMaps.N_BUILDINGS] = Math.max(0, (i - 60) / 20);
            float visibility = Math.min(1.0f, i / 120.0f);
            acc.addSnapshot(new WindowSnapshot(player, opponent, visibility));
        }
    }

    private static void appendRow(StringBuilder sb, String label, long[] sorted) {
        long mean = Arrays.stream(sorted).sum() / sorted.length;
        sb.append(String.format("  %-22s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                label, fmt(mean), fmt(sorted[p(50)]), fmt(sorted[p(95)]), fmt(sorted[p(99)]), fmt(sorted[sorted.length - 1])));
    }

    private static int p(int percentile) {
        return (int) ((percentile / 100.0) * ITERATIONS) - 1;
    }

    private static String fmt(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.1fµs", nanos / 1_000.0);
        return String.format("%.2fms", nanos / 1_000_000.0);
    }
}
