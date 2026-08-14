package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deferred evaluation of advisory recommendations via game state delta comparison.
 *
 * <p>Records pending evaluations when {@link AdvisoryCompleted} fires, capturing a snapshot
 * of game state metrics (minerals, supply, army). Called from {@link GameTickExecutor} each
 * tick to check if any pending evaluation is mature (frame delta >= {@code EVALUATION_DELAY_FRAMES}).
 *
 * <p>When mature: compares snapshot metrics at advisory time vs current time:
 * <ul>
 *   <li>Positive delta → {@link AttestationVerdict#ENDORSED} with advisory confidence
 *   <li>Negative delta → {@link AttestationVerdict#CHALLENGED} with fixed 0.7 confidence
 * </ul>
 *
 * <p>Writes {@link OutcomeRecord} with dimension key {@code recommendation-quality}.
 *
 * <p>Pending evaluations are cleared on {@link GameStarted}.
 *
 * <p>Thread-safe: backed by {@link CopyOnWriteArrayList} to support concurrent modification
 * from advisory completion observers (add) and game tick thread (iterate/remove).
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class DeferredAdvisoryEvaluator {

    private static final Logger log = Logger.getLogger(DeferredAdvisoryEvaluator.class);
    private static final long EVALUATION_DELAY_FRAMES = 200;  // ~17 seconds at 12 FPS

    @Inject OutcomeRecorder outcomeRecorder;
    @Inject GameSession gameSession;

    private final List<PendingEvaluation> pendingEvaluations = new CopyOnWriteArrayList<>();

    /**
     * Observes {@link AdvisoryCompleted} and captures game state snapshot from the event.
     * Package-private for testing.
     */
    void onAdvisoryCompleted(@Observes AdvisoryCompleted event) {
        pendingEvaluations.add(new PendingEvaluation(
            event.advisorId(),
            event.capability(),
            event.gameFrame(),
            event.recommendation(),
            event.confidence(),
            event.gameStateSnapshot()
        ));
        log.debugf("Advisory pending: actor=%s frame=%d snapshot=%s",
            event.advisorId(), event.gameFrame(), event.gameStateSnapshot());
    }

    /**
     * Called from {@link GameTickExecutor#execute()} each tick after engine settle.
     * Evaluates mature pending evaluations and writes {@link OutcomeRecord}s.
     */
    public void evaluate(CaseContext ctx, long currentFrame) {
        for (PendingEvaluation pending : pendingEvaluations) {
            long frameDelta = currentFrame - pending.advisoryFrame();
            if (frameDelta >= EVALUATION_DELAY_FRAMES) {
                evaluateAndRecord(pending, ctx);
                pendingEvaluations.remove(pending);
            }
        }
    }

    private void evaluateAndRecord(PendingEvaluation pending, CaseContext ctx) {
        Map<String, Double> currentSnapshot = captureGameStateSnapshot(ctx);
        double totalDelta = computeTotalDelta(pending.gameStateSnapshot(), currentSnapshot);

        AttestationVerdict verdict;
        double confidence;
        if (totalDelta >= 0) {
            verdict = AttestationVerdict.ENDORSED;
            confidence = pending.confidence();  // use advisory's own confidence
        } else {
            verdict = AttestationVerdict.CHALLENGED;
            confidence = 0.7;  // fixed confidence for challenges (game-level event)
        }

        outcomeRecorder.record(OutcomeRecord.of(
            pending.advisorId(),
            gameSession.id(),
            "recommendation-quality",
            verdict,
            confidence
        ));

        log.debugf("Deferred eval: actor=%s frame=%d→now delta=%.1f verdict=%s",
            pending.advisorId(), pending.advisoryFrame(), totalDelta, verdict);
    }

    private Map<String, Double> captureGameStateSnapshot(CaseContext ctx) {
        Map<String, Double> snapshot = new HashMap<>();
        snapshot.put("minerals", getDoubleOrZero(ctx, QuarkMindCaseFile.MINERALS));
        snapshot.put("supply", getDoubleOrZero(ctx, QuarkMindCaseFile.SUPPLY_USED));
        snapshot.put("army", getDoubleOrZero(ctx, QuarkMindCaseFile.ARMY));
        return snapshot;
    }

    private double getDoubleOrZero(CaseContext ctx, String key) {
        Object value = ctx.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private double computeTotalDelta(Map<String, Double> before, Map<String, Double> after) {
        double delta = 0.0;
        for (String key : before.keySet()) {
            double beforeValue = before.getOrDefault(key, 0.0);
            double afterValue = after.getOrDefault(key, 0.0);
            delta += (afterValue - beforeValue);
        }
        return delta;
    }

    void onGameStarted(@Observes GameStarted event) {
        pendingEvaluations.clear();
        log.debug("Pending evaluations cleared on GameStarted");
    }
}
