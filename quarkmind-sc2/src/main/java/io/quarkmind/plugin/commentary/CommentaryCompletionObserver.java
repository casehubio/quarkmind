package io.quarkmind.plugin.commentary;

import io.quarkmind.agent.LlmWorkerCompleted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * CDI observer that bridges {@link CommentaryCompleted} events to
 * {@link LlmWorkerCompleted} for shared latency recording.
 *
 * <p>Commentary Workers fire {@code CommentaryCompleted} with commentary-specific
 * fields (text, commentaryType). This observer extracts the shared latency fields
 * (workerId, capability, gameFrame, latencyMs) and fires {@code LlmWorkerCompleted}
 * so {@code LlmWorkerLatencyRecorder} can record commentary latency alongside
 * advisory latency.
 *
 * <p>Refs #181
 */
@ApplicationScoped
public class CommentaryCompletionObserver {

    @Inject Event<LlmWorkerCompleted> llmWorkerCompletedEvent;

    void onCommentaryCompleted(@Observes CommentaryCompleted event) {
        llmWorkerCompletedEvent.fire(new LlmWorkerCompleted(
            event.workerId(),
            event.capability(),
            event.gameFrame(),
            event.latencyMs()
        ));
    }
}
