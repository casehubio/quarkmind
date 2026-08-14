package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Records LLM Worker latency (advisory and commentary) as an {@link OutcomeRecord}
 * with dimension key {@code response-latency}.
 *
 * <p>Computes normalised latency score: {@code 1.0 - (actualMs / maxAcceptableMs)}, clamped to [0, 1].
 * Max acceptable latency per capability:
 * <ul>
 *   <li>advisory-crisis: 2000ms
 *   <li>advisory-strategic: 5000ms
 *   <li>advisory-economic: 4000ms
 *   <li>commentary-reactive: 2000ms
 *   <li>commentary-narrative: 5000ms
 *   <li>default (unknown capabilities): 5000ms
 * </ul>
 *
 * <p>Verdict is always {@link AttestationVerdict#ENDORSED} — confidence encodes the score.
 *
 * <p>Confidence 0.1: tick-level outcome (OutcomeRecord Javadoc: 0.1=tick, 0.7=game event, 1.0=session).
 * Latency assessment happens per-worker-tick, not per-game or per-session.
 *
 * <p>Refs #180, #181
 */
@ApplicationScoped
public class LlmWorkerLatencyRecorder {

    private static final Logger log = Logger.getLogger(LlmWorkerLatencyRecorder.class);

    private static final Map<String, Long> MAX_LATENCY_MS = Map.of(
        "advisory-crisis", 2000L,
        "advisory-strategic", 5000L,
        "advisory-economic", 4000L,
        "commentary-reactive", 2000L,
        "commentary-narrative", 5000L
    );
    private static final long DEFAULT_MAX_LATENCY_MS = 5000L;

    @Inject OutcomeRecorder outcomeRecorder;
    @Inject GameSession gameSession;

    void onLlmWorkerCompleted(@Observes LlmWorkerCompleted event) {
        long maxMs = MAX_LATENCY_MS.getOrDefault(event.capability(), DEFAULT_MAX_LATENCY_MS);
        double rawScore = 1.0 - (double) event.latencyMs() / maxMs;
        double confidence = Math.max(0.01, Math.min(1.0, rawScore));  // clamp to (0.01, 1.0]

        outcomeRecorder.record(OutcomeRecord.of(
            event.workerId(),
            gameSession.id(),
            "response-latency",
            AttestationVerdict.ENDORSED,
            confidence
        ));

        log.debugf("Latency: actor=%s capability=%s latency=%dms score=%.2f frame=%d",
            event.workerId(), event.capability(), event.latencyMs(), confidence, event.gameFrame());
    }
}
