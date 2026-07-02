package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Observes {@link AdvisoryCompleted} events and records the invocation in
 * {@link AdvisoryInvocationCounter}.
 *
 * <p>This observer receives the original event fired by {@link AdvisoryWorkerFactory}
 * after successful advisory completion. Other observers ({@link AdvisoryLatencyRecorder},
 * {@link DeferredAdvisoryEvaluator}, {@link AdvisoryChannelBroker}) also observe the
 * event directly via {@code @Observes AdvisoryCompleted} — no re-fire is needed.
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisoryCompletionObserver {

    private final AdvisoryInvocationCounter invocationCounter;

    /**
     * CDI constructor.
     */
    @Inject
    public AdvisoryCompletionObserver(AdvisoryInvocationCounter invocationCounter) {
        this.invocationCounter = invocationCounter;
    }

    /**
     * Observes {@link AdvisoryCompleted} events and records the invocation.
     *
     * <p>Uses {@code @Observes} (synchronous) per protocol PP-20260610-88dbbd.
     */
    void onAdvisoryCompleted(@Observes AdvisoryCompleted event) {
        invocationCounter.record(event.advisorId());
    }
}
