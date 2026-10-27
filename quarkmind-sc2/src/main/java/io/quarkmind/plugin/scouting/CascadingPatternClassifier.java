package io.quarkmind.plugin.scouting;

import io.casehub.api.context.CaseContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.PhaseResolver;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.ScoutingObservation;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.StrategyTransition;
import io.quarkmind.sc2.detector.BinaryDetectorRunner;
import io.quarkmind.sc2.detector.DetectorOrchestrator;
import io.quarkmind.sc2.detector.DetectorRegistry;
import io.quarkmind.sc2.detector.HypothesisPriorLoader;
import io.quarkmind.sc2.detector.HypothesisTrackerFusion;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class CascadingPatternClassifier {

    private static final Logger log = Logger.getLogger(CascadingPatternClassifier.class);

    static final double DISPATCH_THRESHOLD = 0.3;
    static final double DECAY_PER_FRAME    = 0.99948;
    static final double NOISE_FLOOR        = 0.01;
    static final double FALLBACK_CONFIDENCE = 0.35;
    static final double TRANSITION_MIN_CONFIDENCE = 0.4;
    static final double TRANSITION_GAP_MARGIN = 0.15;

    static StrategyArchetype unknownForRace(Race race) {
        return switch (race) {
            case TERRAN -> StrategyArchetype.TERRAN_COMPOSITION_UNKNOWN;
            case ZERG -> StrategyArchetype.ZERG_COMPOSITION_UNKNOWN;
            case PROTOSS -> StrategyArchetype.PROTOSS_COMPOSITION_UNKNOWN;
            default -> StrategyArchetype.PROTOSS_COMPOSITION_UNKNOWN;
        };
    }


    private final double droolsThreshold;
    private final double onnxThreshold;
    private final String fusionMode;
    private final DetectorOrchestrator detectorOrchestrator;
    private final HypothesisTrackerFusion hypothesisTrackerFusion;
    private final BinaryDetectorRunner detectorRunner;
    private final PhaseResolver phaseResolver;

    private final EnumMap<StrategyArchetype, Double> cumulativeConfidence =
            new EnumMap<>(StrategyArchetype.class);

    private boolean llmFallbackEnabled;
    private double llmFallbackConfidenceThreshold = 0.5;
    private long llmFallbackMinGameTimeFrames = 2160;
    private long llmFallbackCooldownFrames = 500;
    private long lastLlmFallbackFrame = -1;
    private String lastProcessedLlmArchetype;
    private StrategyArchetype prevDominant;
    @Inject
    MeterRegistry registry;

    private Counter droolsInvocations, onnxInvocations, llmInvocations;
    private Counter droolsResolutions, onnxResolutions, llmResolutions;

    @PostConstruct
    void initMetrics() {
        if (registry == null) {return;}
        droolsInvocations = registry.counter("quarkmind.classifier.invocations", "tier", "drools");
        onnxInvocations   = registry.counter("quarkmind.classifier.invocations", "tier", "onnx");
        llmInvocations    = registry.counter("quarkmind.classifier.invocations", "tier", "llm");
        droolsResolutions = registry.counter("quarkmind.classifier.resolutions", "tier", "drools");
        onnxResolutions   = registry.counter("quarkmind.classifier.resolutions", "tier", "onnx");
        llmResolutions    = registry.counter("quarkmind.classifier.resolutions", "tier", "llm");
    }

    private void inc(Counter counter) {
        if (counter != null) {counter.increment();}
    }


    @Inject
    public CascadingPatternClassifier(
            @ConfigProperty(name = "quarkmind.classifier.drools.confidence-threshold", defaultValue = "0.7")
            double droolsThreshold,
            @ConfigProperty(name = "quarkmind.classifier.onnx.confidence-threshold", defaultValue = "0.5")
            double onnxThreshold,
            @ConfigProperty(name = "quarkmind.classifier.fusion.mode", defaultValue = "hypothesis")
            String fusionMode,
            BinaryDetectorRunner detectorRunner,
            io.quarkmind.sc2.detector.LearnedCptLoader learnedCptLoader,
            PhaseResolver phaseResolver) {
        this.droolsThreshold = droolsThreshold;
        this.onnxThreshold   = onnxThreshold;
        this.fusionMode      = fusionMode;
        this.detectorRunner  = detectorRunner;
        this.phaseResolver   = phaseResolver;

        if (detectorRunner != null && detectorRunner.registry() != null) {
            Map<String, io.quarkmind.inference.bayes.BayesNetwork> learnedNetworks =
                learnedCptLoader != null ? learnedCptLoader.loadFromClasspath() : Map.of();
            this.detectorOrchestrator = buildDefaultOrchestrator(
                detectorRunner.registry(), learnedNetworks);
            this.hypothesisTrackerFusion = buildDefaultFusion(
                detectorRunner.registry(), phaseResolver);
        } else {
            this.detectorOrchestrator = null;
            this.hypothesisTrackerFusion = null;
        }
    }

    public CascadingPatternClassifier(double droolsThreshold, double onnxThreshold,
                                      DetectorOrchestrator orchestrator,
                                      BinaryDetectorRunner runner) {
        this(droolsThreshold, onnxThreshold, orchestrator, runner, Map.of());
    }

    public CascadingPatternClassifier(double droolsThreshold, double onnxThreshold,
                                      DetectorOrchestrator orchestrator,
                                      BinaryDetectorRunner runner,
                                      Map<String, io.quarkmind.inference.bayes.BayesNetwork> learnedNetworks) {
        this.droolsThreshold = droolsThreshold;
        this.onnxThreshold   = onnxThreshold;
        this.detectorRunner  = runner;
        this.fusionMode      = "detector";
        this.phaseResolver   = null;
        this.hypothesisTrackerFusion = null;
        if (orchestrator != null) {
            this.detectorOrchestrator = orchestrator;
        } else if (runner != null && runner.registry() != null) {
            this.detectorOrchestrator = buildDefaultOrchestrator(runner.registry(), learnedNetworks);
        } else {
            this.detectorOrchestrator = null;
        }
    }

    public CascadingPatternClassifier(double droolsThreshold, double onnxThreshold,
                                      HypothesisTrackerFusion fusion,
                                      BinaryDetectorRunner runner) {
        this.droolsThreshold = droolsThreshold;
        this.onnxThreshold   = onnxThreshold;
        this.hypothesisTrackerFusion = fusion;
        this.detectorOrchestrator = null;
        this.detectorRunner  = runner;
        this.fusionMode      = "hypothesis";
        this.phaseResolver   = null;
    }

    public CascadingPatternClassifier(double droolsThreshold, double onnxThreshold) {
        this(droolsThreshold, onnxThreshold, (DetectorOrchestrator) null, null);
    }


    public void observeScouting(ScoutingObservation observation) {
        if (hypothesisTrackerFusion != null && "hypothesis".equals(fusionMode)) {
            hypothesisTrackerFusion.observeScouting(observation);
        }
    }

    public HypothesisTrackerFusion hypothesisTrackerFusion() {
        return hypothesisTrackerFusion;
    }


    public void setLlmFallbackConfig(boolean enabled, double confidenceThreshold,
                                     long minGameTimeFrames, long cooldownFrames) {
        this.llmFallbackEnabled = enabled;
        this.llmFallbackConfidenceThreshold = confidenceThreshold;
        this.llmFallbackMinGameTimeFrames = minGameTimeFrames;
        this.llmFallbackCooldownFrames = cooldownFrames;
    }

    public CascadeResult classify(List<EvidenceMarker> evidence,
                                  List<ConfidenceRevision> revisions,
                                  StrategyFeatures onnxFeatures,
                                  Race enemyRace,
                                  long frame, long prevFrame,
                                  CaseContext ctx) {
        long framesElapsed = prevFrame >= 0 ? frame - prevFrame : 0;

        mergeCumulative(cumulativeConfidence, computeAllConfidences(evidence), frame, prevFrame);
        applyRevisions(cumulativeConfidence, revisions, framesElapsed);

        // Transition detection — after cumulative update, before tier routing
        StrategyArchetype currentDominant = findDominant(cumulativeConfidence);
        StrategyTransition transition = null;
        if (currentDominant != null && currentDominant != prevDominant) {
            if (prevDominant == null) {
                prevDominant = currentDominant;
            } else {
                double currentConf = cumulativeConfidence.getOrDefault(currentDominant, 0.0);
                double prevConf = cumulativeConfidence.getOrDefault(prevDominant, 0.0);
                if (currentConf >= TRANSITION_MIN_CONFIDENCE
                        && currentConf > prevConf + TRANSITION_GAP_MARGIN) {
                    transition = new StrategyTransition(
                        prevDominant, currentDominant, prevConf, currentConf, frame, null);
                    prevDominant = currentDominant;
                }
            }
        }

        // Integrate LLM result from previous tick (if any)
        if (ctx != null) {
            String prevLlmArch = lastProcessedLlmArchetype;
            lastProcessedLlmArchetype = processLlmFallbackResult(ctx, cumulativeConfidence, lastProcessedLlmArchetype);
            if (!Objects.equals(prevLlmArch, lastProcessedLlmArchetype) && lastProcessedLlmArchetype != null) {
                inc(llmResolutions);
                return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.LLM), false, transition);
            }
        }

        double maxConfidence = cumulativeConfidence.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        // Tier 1: Drools — high confidence from rule evidence
        inc(droolsInvocations);
        if (maxConfidence >= droolsThreshold) {
            inc(droolsResolutions);
            return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), false, transition);
        }

        // Tier 2: Bayesian fusion
        if (onnxFeatures != null) {
            GamePhase phase = resolvePhaseFromContext(ctx, frame);

            if ("hypothesis".equals(fusionMode) && hypothesisTrackerFusion != null) {
                CascadeResult fusionResult = classifyWithHypothesisTracker(
                    onnxFeatures, enemyRace, phase, frame, transition);
                if (fusionResult != null) return fusionResult;
            } else if (detectorOrchestrator != null) {
                CascadeResult detectorResult = classifyWithDetectorOrchestrator(
                    onnxFeatures, enemyRace, phase, frame, transition);
                if (detectorResult != null) return detectorResult;
            }
        }

        // Tier 3: LLM fallback trigger
        boolean llmTriggered = false;
        if (llmFallbackEnabled && ctx != null && shouldFireLlmFallback(
                cumulativeConfidence, llmFallbackConfidenceThreshold, frame,
                llmFallbackMinGameTimeFrames, lastLlmFallbackFrame, llmFallbackCooldownFrames)) {
            inc(llmInvocations);
            lastLlmFallbackFrame = frame;
            ctx.set(QuarkMindCaseFile.LLM_FALLBACK_TRIGGER, Map.of(
                    "gameFrame", frame,
                    "cumulativeConfidences", snapshotConfidences()));
            log.infof("[CASCADE] LLM fallback triggered at frame %d — all confidences below %.2f",
                    frame, llmFallbackConfidenceThreshold);
            llmTriggered = true;
        }

        return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), llmTriggered, transition);
    }

    public CascadeResult classify(List<EvidenceMarker> evidence,
                                  List<ConfidenceRevision> revisions,
                                  StrategyFeatures onnxFeatures,
                                  Race enemyRace,
                                  long frame, long prevFrame,
                                  CaseContext ctx, int enemyCount) {
        CascadeResult result = classify(evidence, revisions, onnxFeatures,
                                        enemyRace, frame, prevFrame, ctx);
        if (result.assessments().isEmpty() && enemyCount > 0 && enemyRace != null) {
            StrategyArchetype unknown = unknownForRace(enemyRace);
            return new CascadeResult(List.of(new PatternAssessment(
                    unknown, FALLBACK_CONFIDENCE, frame,
                    "Enemies visible but no archetype matched — composition unrecognised",
                    AssessmentSource.DROOLS)), result.llmTriggered(), result.transition());
        }
        return result;
    }


    private CascadeResult classifyWithHypothesisTracker(
            StrategyFeatures onnxFeatures, Race enemyRace,
            GamePhase phase, long frame, StrategyTransition transition) {
        inc(onnxInvocations);
        if (detectorRunner != null && enemyRace != null) {
            var allActive = detectorRunner.registry().getActiveDetectors(phase, enemyRace);
            var allKeys = new HashSet<String>();
            for (var def : allActive) { allKeys.add(def.key(enemyRace)); }
            if (!allKeys.isEmpty()) {
                hypothesisTrackerFusion.initialize(List.copyOf(allKeys), phase, enemyRace);
                var toEvaluate = hypothesisTrackerFusion.getDetectorsToEvaluate(allKeys);
                var detectorOutputs = detectorRunner.runActiveDetectors(
                    phase, enemyRace, onnxFeatures, toEvaluate);
                var fusionAssessments = hypothesisTrackerFusion.classify(
                    detectorOutputs, List.of(), frame);
                if (!fusionAssessments.isEmpty()
                        && fusionAssessments.get(0).confidence() >= onnxThreshold) {
                    for (var a : fusionAssessments) {
                        cumulativeConfidence.merge(a.archetype(), a.confidence(), Math::max);
                    }
                    inc(onnxResolutions);
                    var mapStrategy = hypothesisTrackerFusion.getMapStrategy();
                    return new CascadeResult(
                        allAssessments(cumulativeConfidence, frame, AssessmentSource.FUSION),
                        false, transition, mapStrategy);
                }
            }
        }
        return null;
    }

    private CascadeResult classifyWithDetectorOrchestrator(
            StrategyFeatures onnxFeatures, Race enemyRace,
            GamePhase phase, long frame, StrategyTransition transition) {
        inc(onnxInvocations);
        Map<String, Float> detectorOutputs = Map.of();
        if (detectorRunner != null && enemyRace != null) {
            var allActive = detectorRunner.registry().getActiveDetectors(phase, enemyRace);
            var allKeys = new HashSet<String>();
            for (var def : allActive) { allKeys.add(def.key(enemyRace)); }
            if (!allKeys.isEmpty()) {
                detectorOrchestrator.initializeNetwork(List.copyOf(allKeys), phase, enemyRace);
                var toEvaluate = detectorOrchestrator.getDetectorsToEvaluate(allKeys);
                detectorOutputs = detectorRunner.runActiveDetectors(phase, enemyRace, onnxFeatures, toEvaluate);
            }
        }
        var fusionAssessments = detectorOrchestrator.classify(
            detectorOutputs, List.of(), frame);
        if (!fusionAssessments.isEmpty() && fusionAssessments.get(0).confidence() >= onnxThreshold) {
            for (var assessment : fusionAssessments) {
                cumulativeConfidence.merge(assessment.archetype(), assessment.confidence(), Math::max);
            }
            inc(onnxResolutions);
            var mapStrategy = detectorOrchestrator.getMapStrategy();
            return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.FUSION), false, transition, mapStrategy);
        }
        return null;
    }

    private GamePhase resolvePhaseFromContext(CaseContext ctx, long frame) {
        if (phaseResolver != null && ctx != null) {
            GameState gs = ctx.getAs(QuarkMindCaseFile.GAME_STATE, GameState.class);
            if (gs != null) {
                return phaseResolver.resolve(gs);
            }
        }
        return resolvePhase(frame);
    }

    public void reset() {
        cumulativeConfidence.clear();
        lastLlmFallbackFrame = -1;
        lastProcessedLlmArchetype = null;
        prevDominant = null;
        if (hypothesisTrackerFusion != null) {
            hypothesisTrackerFusion.reset();
        }
        if (detectorOrchestrator != null) {
            detectorOrchestrator.reset();
        }
    }

    Map<String, Double> snapshotConfidences() {
        var snapshot = new HashMap<String, Double>();
        cumulativeConfidence.forEach((arch, conf) -> snapshot.put(arch.name(), conf));
        return snapshot;
    }

    io.quarkmind.agent.plugin.DetectorStatsPublished detectorStats() {
        if ("hypothesis".equals(fusionMode) && hypothesisTrackerFusion != null) {
            var mapStrategy = hypothesisTrackerFusion.getMapStrategy();
            return new io.quarkmind.agent.plugin.DetectorStatsPublished(
                    0, 0, 0, 0,
                    hypothesisTrackerFusion.getSensitivities(),
                    mapStrategy != null ? mapStrategy.name() : null,
                    hypothesisTrackerFusion.getMapAssignment(),
                    hypothesisTrackerFusion.getExplanationSummary());
        }
        if (detectorOrchestrator == null) {return null;}
        var stats = detectorOrchestrator.getStats();
        if (stats == null) {return null;}
        var mapStrategy = detectorOrchestrator.getMapStrategy();
        return new io.quarkmind.agent.plugin.DetectorStatsPublished(
                stats.dirtyVariableCount(),
                stats.lastPropagationNanos(),
                stats.retractionCount(),
                stats.evidenceVariableCount(),
                detectorOrchestrator.getSensitivities(),
                mapStrategy != null ? mapStrategy.name() : null,
                detectorOrchestrator.getMapAssignment(),
                detectorOrchestrator.getExplanationSummary());
    }

    String explanationSummary() {
        if ("hypothesis".equals(fusionMode) && hypothesisTrackerFusion != null) {
            return hypothesisTrackerFusion.getExplanationSummary();
        }
        if (detectorOrchestrator == null) {return null;}
        return detectorOrchestrator.getExplanationSummary();
    }


    static boolean shouldFireLlmFallback(EnumMap<StrategyArchetype, Double> cumulativeConfidence,
                                         double threshold, long currentFrame, long minGameTimeFrames,
                                         long lastFallbackFrame, long cooldownFrames) {
        if (currentFrame < minGameTimeFrames) { return false; }
        if (lastFallbackFrame >= 0 && currentFrame - lastFallbackFrame < cooldownFrames) { return false; }
        for (double conf : cumulativeConfidence.values()) {
            if (conf >= threshold) { return false; }
        }
        return true;
    }

    static String processLlmFallbackResult(CaseContext ctx,
                                           EnumMap<StrategyArchetype, Double> cumulativeConfidence,
                                           String lastProcessedArchetype) {
        String archetypeName = ctx.getAs(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, String.class);
        if (archetypeName == null) { return lastProcessedArchetype; }
        if (archetypeName.equals(lastProcessedArchetype)) { return lastProcessedArchetype; }

        String confStr = ctx.getAs(QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, String.class);

        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, null);
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, null);
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_RATIONALE, null);

        StrategyArchetype archetype;
        try {
            archetype = StrategyArchetype.valueOf(archetypeName);
        } catch (IllegalArgumentException e) {
            log.warnf("[CASCADE] Invalid LLM fallback archetype: '%s'", archetypeName);
            return archetypeName;
        }

        double confidence = 0.6;
        if (confStr != null) {
            try { confidence = Double.parseDouble(confStr); } catch (NumberFormatException ignored) {}
        }

        cumulativeConfidence.put(archetype, confidence);
        return archetypeName;
    }

    static double computeTickConfidence(List<EvidenceMarker> markers) {
        if (markers.isEmpty()) { return 0.0; }
        double product = 1.0;
        for (EvidenceMarker m : markers) {
            product *= (1.0 - m.weight());
        }
        return 1.0 - product;
    }

    static Map<StrategyArchetype, Double> computeAllConfidences(List<EvidenceMarker> markers) {
        return markers.stream()
                .collect(Collectors.groupingBy(EvidenceMarker::archetype))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> computeTickConfidence(e.getValue())));
    }

    static void mergeCumulative(EnumMap<StrategyArchetype, Double> cumulative,
                                        Map<StrategyArchetype, Double> thisTick,
                                        long currentFrame, long lastFrame) {
        if (lastFrame >= 0) {
            long elapsed = currentFrame - lastFrame;
            double decay = Math.pow(DECAY_PER_FRAME, elapsed);
            cumulative.replaceAll((arch, conf) -> conf * decay);
        }
        thisTick.forEach((arch, conf) ->
                cumulative.merge(arch, conf, Math::max));
        cumulative.values().removeIf(v -> v < NOISE_FLOOR);
    }

    static void applyRevisions(EnumMap<StrategyArchetype, Double> cumulative,
                                       List<ConfidenceRevision> revisions,
                                       long framesElapsed) {
        for (ConfidenceRevision rev : revisions) {
            cumulative.computeIfPresent(rev.archetype(), (arch, conf) ->
                    Math.max(0, conf * Math.pow(rev.dampingFactor(), framesElapsed)));
        }
    }

    static List<PatternAssessment> allAssessments(
            EnumMap<StrategyArchetype, Double> cumulative, long frame, AssessmentSource source) {
        return cumulative.entrySet().stream()
                .filter(e -> e.getValue() >= DISPATCH_THRESHOLD)
                .sorted(Map.Entry.<StrategyArchetype, Double>comparingByValue().reversed())
                .map(e -> new PatternAssessment(e.getKey(), e.getValue(), frame,
                        e.getKey().name() + " (confidence " +
                                String.format("%.2f", e.getValue()) + ")",
                        source))
                .toList();
    }

    static StrategyArchetype findDominant(EnumMap<StrategyArchetype, Double> cumulative) {
        StrategyArchetype best     = null;
        double            bestConf = NOISE_FLOOR;
        for (var e : cumulative.entrySet()) {
            if (e.getValue() > bestConf) {
                bestConf = e.getValue();
                best     = e.getKey();
            }
        }
        return best;
    }

    static GamePhase resolvePhase(long frame) {
        double minutes = frame / (22.4 * 60.0);
        if (minutes < 3.0) {return GamePhase.EARLY;}
        if (minutes < 5.0) {return GamePhase.MID;}
        return GamePhase.LATE;
    }

    private static DetectorOrchestrator buildDefaultOrchestrator(
            DetectorRegistry registry,
            Map<String, io.quarkmind.inference.bayes.BayesNetwork> learnedNetworks) {
        var config        = registry.config();
        var allArchetypes = new LinkedHashSet<String>();
        for (var def : config.detectors()) {
            for (var archetypes : def.positiveArchetypes().values()) {
                allArchetypes.addAll(archetypes);
            }
        }
        Map<String, Double> priors = new HashMap<>();
        for (String arch : allArchetypes) {
            priors.put(arch, 1.0 / allArchetypes.size());
        }
        Map<String, Map<String, Double>> cpts = new HashMap<>();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String              key = def.key(entry.getKey());
                Map<String, Double> cpt = new HashMap<>();
                for (String arch : allArchetypes) {
                    cpt.put(arch, entry.getValue().contains(arch) ? 0.8 : 0.2);
                }
                cpts.put(key, cpt);
            }
        }
        Map<String, StrategyArchetype> labelToArchetype = new HashMap<>();
        for (String arch : allArchetypes) {
            try {
                labelToArchetype.put(arch, StrategyArchetype.valueOf(arch));
            } catch (IllegalArgumentException ignored) {}
        }
        return new DetectorOrchestrator(registry, priors, cpts, labelToArchetype, 5, 0.95, 0.01, learnedNetworks);
    }

    private static HypothesisTrackerFusion buildDefaultFusion(
            DetectorRegistry registry, PhaseResolver phaseResolver) {
        var config = registry.config();
        var allArchetypes = new LinkedHashSet<String>();
        for (var def : config.detectors()) {
            for (var archetypes : def.positiveArchetypes().values()) {
                allArchetypes.addAll(archetypes);
            }
        }
        Map<String, Map<String, Double>> cpts = new HashMap<>();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String key = def.key(entry.getKey());
                Map<String, Double> cpt = new HashMap<>();
                for (String arch : allArchetypes) {
                    cpt.put(arch, entry.getValue().contains(arch) ? 0.8 : 0.2);
                }
                cpts.put(key, cpt);
            }
        }
        Map<String, Double> defaultPriors = Map.of();
        HypothesisPriorLoader.ObservationCpts defaultObsCpts =
            new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());
        try {
            defaultPriors = HypothesisPriorLoader.loadOpponentPriors(
                GamePhase.EARLY, Race.TERRAN);
            defaultObsCpts = HypothesisPriorLoader.loadObservationCpts(
                GamePhase.EARLY, Race.TERRAN);
        } catch (IllegalArgumentException ignored) {}
        return new HypothesisTrackerFusion(
            registry, defaultPriors, cpts, defaultObsCpts, Map.of(), phaseResolver);
    }
}
