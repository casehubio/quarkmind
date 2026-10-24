package io.quarkmind.sc2.detector;

import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.ClassificationResult;
import io.casehub.neocortex.inference.tasks.TensorClassifier;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.plugin.scouting.FeatureIndexMaps;
import io.quarkmind.plugin.scouting.StrategyFeatures;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@jakarta.enterprise.context.ApplicationScoped
public class BinaryDetectorRunner implements AutoCloseable {

    private static final Logger       log           = Logger.getLogger(BinaryDetectorRunner.class);
    private static final List<String> BINARY_LABELS = List.of("negative", "positive");

    static final int PYTHON_FEATURES_PER_PLAYER = FeatureIndexMaps.SPATIAL_OFFSET;
    static final int ONNX_FEATURES_PER_WINDOW   = 2 * PYTHON_FEATURES_PER_PLAYER + 1;
    static final int ONNX_MAP_FEATURES          = 4;
    static final int MAX_WINDOWS                = 10;

    private       DetectorRegistry                registry;
    private final Map<String, TensorClassifier>   classifiers = new ConcurrentHashMap<>();
    private final Map<String, OnnxInferenceModel> models      = new ConcurrentHashMap<>();

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkmind.detector.models-dir", defaultValue = "")
    String configModelsDir;

    BinaryDetectorRunner() {}

    public BinaryDetectorRunner(Path modelsDir) {
        var config = DetectorCatalog.create();
        this.registry = new DetectorRegistry(config);
        loadAllModels(config, modelsDir);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        if (registry != null) {return;}
        var config = DetectorCatalog.create();
        this.registry = new DetectorRegistry(config);
        Path modelsDir = resolveModelsDir();
        loadAllModels(config, modelsDir);
    }

    private Path resolveModelsDir() {
        if (configModelsDir != null && !configModelsDir.isEmpty()) {
            return Path.of(configModelsDir);
        }
        var url = getClass().getClassLoader().getResource("models/detectors");
        if (url != null) {
            try {
                return Path.of(url.toURI());
            } catch (Exception e) {
                log.warnf("Cannot resolve classpath models dir: %s", e.getMessage());
            }
        }
        return Path.of("src/main/resources/models/detectors");
    }

    @jakarta.annotation.PreDestroy
    void destroy() {
        close();
    }

    private void loadAllModels(DetectorCatalogConfig config, Path modelsDir) {
        for (var def : config.detectors()) {
            for (Race race : def.positiveArchetypes().keySet()) {
                String key       = def.key(race);
                Path   modelPath = modelsDir.resolve("strategy_" + key + ".onnx");
                try {
                    var model = new OnnxInferenceModel(new ModelConfig(modelPath));
                    models.put(key, model);
                    classifiers.put(key, new TensorClassifier(model, BINARY_LABELS));
                    log.debugf("Loaded detector: %s", key);
                } catch (Exception e) {
                    log.warnf("Failed to load detector %s: %s", key, e.getMessage());
                }
            }
        }
        log.infof("Loaded %d/%d binary detectors",
                  classifiers.size(), config.detectors().stream()
                                            .mapToLong(d -> d.positiveArchetypes().size()).sum());
    }

    public Map<String, Float> runActiveDetectors(GamePhase phase, Race enemyRace,
                                                 StrategyFeatures features) {
        return runActiveDetectors(phase, enemyRace, features, null);
    }

    public Map<String, Float> runActiveDetectors(GamePhase phase, Race enemyRace,
                                                 StrategyFeatures features,
                                                 java.util.Set<String> detectorKeysToRun) {
        List<DetectorCatalogConfig.DetectorDef> active =
                registry.getActiveDetectors(phase, enemyRace);
        if (active.isEmpty()) {return Map.of();}

        float[]                javaFlat = features.tensors().get("temporal")[0];
        float[]                javaMap  = features.tensors().get("map")[0];
        Map<String, float[][]> adapted  = adaptFeatures(javaFlat, javaMap);

        Map<String, Float> results = new HashMap<>();
        for (var def : active) {
            String key = def.key(enemyRace);
            if (detectorKeysToRun != null && !detectorKeysToRun.contains(key)) {continue;}
            TensorClassifier classifier = classifiers.get(key);
            if (classifier == null) {continue;}

            ClassificationResult result = classifier.classify(adapted);
            if (result == null) {continue;}

            float pPositive;
            if ("positive".equals(result.label())) {
                pPositive = (float) result.confidence();
            } else {
                pPositive = 1.0f - (float) result.confidence();
            }
            results.put(key, pPositive);
        }
        return results;
    }


    static Map<String, float[][]> adaptFeatures(float[] javaFlat, float[] javaMap) {
        int javaFPW = FeatureIndexMaps.FEATURES_PER_WINDOW;
        int javaNP  = FeatureIndexMaps.N_FEATURES_PER_PLAYER;
        int pyNP    = PYTHON_FEATURES_PER_PLAYER;

        float[] onnxFlat = new float[MAX_WINDOWS * ONNX_FEATURES_PER_WINDOW];
        for (int w = 0; w < MAX_WINDOWS; w++) {
            int javaSrc = w * javaFPW;
            int onnxDst = w * ONNX_FEATURES_PER_WINDOW;

            System.arraycopy(javaFlat, javaSrc, onnxFlat, onnxDst, pyNP);
            System.arraycopy(javaFlat, javaSrc + javaNP, onnxFlat, onnxDst + pyNP, pyNP);
            onnxFlat[onnxDst + 2 * pyNP] = javaFlat[javaSrc + FeatureIndexMaps.HAS_VISION_INDEX];
        }

        float[] onnxMap = new float[ONNX_MAP_FEATURES];
        System.arraycopy(javaMap, 0, onnxMap, 0, ONNX_MAP_FEATURES);

        return Map.of(
                "temporal", new float[][]{onnxFlat},
                "map", new float[][]{onnxMap});
    }

    public DetectorRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        models.values().forEach(OnnxInferenceModel::close);
        models.clear();
        classifiers.clear();
    }
}
