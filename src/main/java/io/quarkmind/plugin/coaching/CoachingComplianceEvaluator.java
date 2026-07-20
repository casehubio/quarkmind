package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CoachingComplianceEvaluator {

    private static final int DEFAULT_AUTO_EXPIRE_FRAMES = 900;

    private final ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments;
    private final CoachingEffectivenessTrustRecorder                recorder;
    private final int                                               autoExpireFrames;

    @Inject
    CoachingComplianceEvaluator(CoachingChannelBroker broker,
                                CoachingEffectivenessTrustRecorder recorder,
                                @ConfigProperty(name = "quarkmind.coaching.compliance.auto-expire-frames",
                                                defaultValue = "900")
                                int autoExpireFrames) {
        this.commitments      = broker.commitments();
        this.recorder         = recorder;
        this.autoExpireFrames = autoExpireFrames;
    }

    CoachingComplianceEvaluator(
            ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments,
            CoachingEffectivenessTrustRecorder recorder) {
        this(commitments, recorder, DEFAULT_AUTO_EXPIRE_FRAMES);
    }

    CoachingComplianceEvaluator(
            ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments,
            CoachingEffectivenessTrustRecorder recorder,
            int autoExpireFrames) {
        this.commitments      = commitments;
        this.recorder         = recorder;
        this.autoExpireFrames = autoExpireFrames;
    }

    public void evaluate(GameState state, long currentFrame) {
        var iterator = commitments.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry      = iterator.next();
            var commitment = entry.getValue();
            var advice     = commitment.advice();

            long windowEnd = commitment.issuedAtFrame() + advice.verificationWindowFrames();
            long expireEnd = commitment.issuedAtFrame() + autoExpireFrames;

            if (!advice.isVerifiable()) {
                if (currentFrame >= windowEnd) {
                    recorder.record(commitment.correlationId(), "NEUTRAL", advice);
                    iterator.remove();
                }
                continue;
            }

            if (currentFrame >= windowEnd) {
                int currentCount = countUnitsOrBuildings(state, advice);
                int delta        = currentCount - commitment.baselineCount();

                if (delta >= advice.verificationCountDelta()) {
                    recorder.record(commitment.correlationId(), "ENDORSED", advice);
                    iterator.remove();
                } else if (currentFrame >= expireEnd) {
                    recorder.record(commitment.correlationId(), "CHALLENGED", advice);
                    iterator.remove();
                }
            }
        }
    }

    private int countUnitsOrBuildings(GameState state, CoachingAdvice advice) {
        if (advice.verificationUnitType() != null) {
            return (int) state.myUnits().stream()
                              .filter(u -> u.type() == advice.verificationUnitType())
                              .count();
        }
        if (advice.verificationBuildingType() != null) {
            return (int) state.myBuildings().stream()
                              .filter(b -> b.type() == advice.verificationBuildingType())
                              .count();
        }
        return 0;
    }

    public void withdrawAll() {
        commitments.forEach((domain, commitment) ->
                                    recorder.record(commitment.correlationId(), "NEUTRAL", commitment.advice()));
        commitments.clear();
    }
}
