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
    private final LocationResolver                                  locationResolver;
    private final int                                               autoExpireFrames;
    @jakarta.inject.Inject
                  jakarta.enterprise.event.Event<CoachingComplianceResolved> complianceResolvedEvent;


    @Inject
    CoachingComplianceEvaluator(CoachingChannelBroker broker,
                                CoachingEffectivenessTrustRecorder recorder,
                                LocationResolver locationResolver,
                                @ConfigProperty(name = "quarkmind.coaching.compliance.auto-expire-frames",
                                                defaultValue = "900")
                                int autoExpireFrames) {
        this.commitments      = broker.commitments();
        this.recorder         = recorder;
        this.locationResolver = locationResolver;
        this.autoExpireFrames = autoExpireFrames;
    }

    CoachingComplianceEvaluator(
            ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments,
            CoachingEffectivenessTrustRecorder recorder,
            LocationResolver locationResolver) {
        this(commitments, recorder, locationResolver, DEFAULT_AUTO_EXPIRE_FRAMES);
    }

    CoachingComplianceEvaluator(
            ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments,
            CoachingEffectivenessTrustRecorder recorder,
            LocationResolver locationResolver,
            int autoExpireFrames) {
        this.commitments      = commitments;
        this.recorder         = recorder;
        this.locationResolver = locationResolver;
        this.autoExpireFrames = autoExpireFrames;
    }

    public void evaluate(GameState state, long currentFrame) {
        var iterator = commitments.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry      = iterator.next();
            var domain     = entry.getKey();
            var commitment = entry.getValue();
            var advice     = commitment.advice();

            long windowEnd = commitment.issuedAtFrame() + advice.verificationWindowFrames();
            long expireEnd = commitment.issuedAtFrame() + autoExpireFrames;

            if (!advice.isVerifiable()) {
                if (currentFrame >= windowEnd) {
                    recorder.record(commitment.correlationId(), commitment.agentId(), "NEUTRAL", advice);
                    fireComplianceResolved(currentFrame, domain, "NEUTRAL", commitment.correlationId());
                    iterator.remove();
                }
                continue;
            }

            if (currentFrame >= windowEnd) {
                if (advice.verification().isSatisfied(state, locationResolver)) {
                    recorder.record(commitment.correlationId(), commitment.agentId(), "ENDORSED", advice);
                    fireComplianceResolved(currentFrame, domain, "ENDORSED", commitment.correlationId());
                    iterator.remove();
                } else if (currentFrame >= expireEnd) {
                    recorder.record(commitment.correlationId(), commitment.agentId(), "CHALLENGED", advice);
                    fireComplianceResolved(currentFrame, domain, "CHALLENGED", commitment.correlationId());
                    iterator.remove();
                }
            }
        }
    }

    public void withdrawAll() {
        commitments.forEach((domain, commitment) ->
                                    recorder.record(commitment.correlationId(), commitment.agentId(), "NEUTRAL", commitment.advice()));
        commitments.clear();
    }

    private void fireComplianceResolved(long frame, CoachingDomain domain, String status, String correlationId) {
        if (complianceResolvedEvent != null) {
            complianceResolvedEvent.fire(new CoachingComplianceResolved(frame, domain, status, correlationId));
        }
    }

}
