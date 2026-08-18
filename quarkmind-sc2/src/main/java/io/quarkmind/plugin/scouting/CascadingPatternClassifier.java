package io.quarkmind.plugin.scouting;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class CascadingPatternClassifier {

    private static final Logger log = Logger.getLogger(CascadingPatternClassifier.class);

    static final double DISPATCH_THRESHOLD = 0.3;
    static final double DECAY_PER_FRAME    = 0.99948;
    static final double NOISE_FLOOR        = 0.01;

    private final double droolsThreshold;
    private final double onnxThreshold;
    private final EnumMap<StrategyArchetype, Double> cumulativeConfidence =
            new EnumMap<>(StrategyArchetype.class);

    private boolean llmFallbackEnabled;
    private double llmFallbackConfidenceThreshold = 0.5;
    private long llmFallbackMinGameTimeFrames = 2160;
    private long llmFallbackCooldownFrames = 500;
    private long lastLlmFallbackFrame = -1;
    private String lastProcessedLlmArchetype;

    @Inject
    public CascadingPatternClassifier(
            @ConfigProperty(name = "quarkmind.classifier.drools.confidence-threshold", defaultValue = "0.7")
            double droolsThreshold,
            @ConfigProperty(name = "quarkmind.classifier.onnx.confidence-threshold", defaultValue = "0.5")
            double onnxThreshold) {
        this.droolsThreshold = droolsThreshold;
        this.onnxThreshold = onnxThreshold;
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
                                  Map<String, float[][]> onnxFeatures,
                                  long frame, long prevFrame,
                                  CaseContext ctx) {
        long framesElapsed = prevFrame >= 0 ? frame - prevFrame : 0;

        mergeCumulative(cumulativeConfidence, computeAllConfidences(evidence), frame, prevFrame);
        applyRevisions(cumulativeConfidence, revisions, framesElapsed);

        // Integrate LLM result from previous tick (if any)
        if (ctx != null) {
            String prevLlmArch = lastProcessedLlmArchetype;
            lastProcessedLlmArchetype = processLlmFallbackResult(ctx, cumulativeConfidence, lastProcessedLlmArchetype);
            if (!java.util.Objects.equals(prevLlmArch, lastProcessedLlmArchetype) && lastProcessedLlmArchetype != null) {
                return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.LLM), false);
            }
        }

        double maxConfidence = cumulativeConfidence.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        if (maxConfidence >= droolsThreshold) {
            return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), false);
        }

        // ONNX tier placeholder — wired in Task 5

        // LLM fallback trigger
        boolean llmTriggered = false;
        if (llmFallbackEnabled && ctx != null && shouldFireLlmFallback(
                cumulativeConfidence, llmFallbackConfidenceThreshold, frame,
                llmFallbackMinGameTimeFrames, lastLlmFallbackFrame, llmFallbackCooldownFrames)) {
            lastLlmFallbackFrame = frame;
            ctx.set(QuarkMindCaseFile.LLM_FALLBACK_TRIGGER, Map.of(
                    "gameFrame", frame,
                    "cumulativeConfidences", snapshotConfidences()));
            log.infof("[CASCADE] LLM fallback triggered at frame %d — all confidences below %.2f",
                    frame, llmFallbackConfidenceThreshold);
            llmTriggered = true;
        }

        return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), llmTriggered);
    }

    public void reset() {
        cumulativeConfidence.clear();
        lastLlmFallbackFrame = -1;
        lastProcessedLlmArchetype = null;
    }

    Map<String, Double> snapshotConfidences() {
        var snapshot = new java.util.HashMap<String, Double>();
        cumulativeConfidence.forEach((arch, conf) -> snapshot.put(arch.name(), conf));
        return snapshot;
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
}
