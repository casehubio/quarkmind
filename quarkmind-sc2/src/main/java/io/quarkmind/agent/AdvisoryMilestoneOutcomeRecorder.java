package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.domain.DominanceScore;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class AdvisoryMilestoneOutcomeRecorder {

    private static final Logger log = Logger.getLogger(AdvisoryMilestoneOutcomeRecorder.class);

    private final OutcomeRecorder outcomeRecorder;
    private final AdvisoryInvocationCounter invocationCounter;
    private final GameSession gameSession;
    private final AdvisoryMilestoneSession advisorySession;
    private final DominanceAssessor dominanceAssessor;
    private final List<MilestoneTrigger> triggers;
    private final boolean milestonesEnabled;
    private final double deadZoneThreshold;

    @Inject
    AdvisoryMilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            AdvisoryInvocationCounter invocationCounter,
            GameSession gameSession,
            AdvisoryMilestoneSession advisorySession,
            DominanceAssessor dominanceAssessor,
            @Any Instance<MilestoneTrigger> triggerInstances,
            MilestoneConfig config) {
        this.outcomeRecorder = outcomeRecorder;
        this.invocationCounter = invocationCounter;
        this.gameSession = gameSession;
        this.advisorySession = advisorySession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers = triggerInstances.stream().toList();
        this.milestonesEnabled = config.enabled();
        this.deadZoneThreshold = config.deadZoneThreshold();
    }

    AdvisoryMilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            AdvisoryInvocationCounter invocationCounter,
            GameSession gameSession,
            AdvisoryMilestoneSession advisorySession,
            DominanceAssessor dominanceAssessor,
            List<MilestoneTrigger> triggers,
            boolean milestonesEnabled,
            double deadZoneThreshold) {
        this.outcomeRecorder = outcomeRecorder;
        this.invocationCounter = invocationCounter;
        this.gameSession = gameSession;
        this.advisorySession = advisorySession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers = triggers;
        this.milestonesEnabled = milestonesEnabled;
        this.deadZoneThreshold = deadZoneThreshold;
    }

    void onGameStarted(@Observes GameStarted event) {
        advisorySession.reset();
    }

    public void evaluateMilestones(GameState state) {
        if (!milestonesEnabled) { return; }

        Set<String> invokedAdvisors = invocationCounter.snapshot();
        if (invokedAdvisors.isEmpty()) { return; }

        for (MilestoneTrigger trigger : triggers) {
            List<MilestoneEvent> events = trigger.check(state.gameFrame(), advisorySession);
            for (MilestoneEvent event : events) {
                DominanceScore score = dominanceAssessor.assess(state);
                if (Math.abs(score.overall()) < deadZoneThreshold) {
                    log.debugf("[ADVISORY-MILESTONE] Dead zone — skipped %s (score=%.3f)",
                        event.milestoneId(), score.overall());
                    continue;
                }

                AttestationVerdict verdict = score.overall() > 0
                    ? AttestationVerdict.ENDORSED
                    : AttestationVerdict.CHALLENGED;
                double confidence = event.temporalWeight() * Math.abs(score.overall());

                for (String advisorId : invokedAdvisors) {
                    recordAdvisoryMilestone(advisorId, verdict, confidence, event.milestoneId());
                }
            }
        }
    }

    private void recordAdvisoryMilestone(String advisorId, AttestationVerdict verdict,
                                         double confidence, String milestoneId) {
        var existingEntry = advisorySession.entryId(advisorId);
        if (existingEntry.isPresent()) {
            outcomeRecorder.addAttestation(existingEntry.get(), verdict, confidence, "game-outcome");
        } else {
            UUID entryId = outcomeRecorder.record(OutcomeRecord.of(
                advisorId, gameSession.id(), "game-outcome", verdict, confidence));
            advisorySession.setEntryId(advisorId, entryId);
        }
        log.debugf("[ADVISORY-MILESTONE] Recorded: advisor=%s milestone=%s verdict=%s confidence=%.3f",
            advisorId, milestoneId, verdict, confidence);
    }

    void onGameStopped(@Observes GameStopped event) {
        if (event.result() == GameResult.UNKNOWN) {
            log.infof("[ADVISORY-MILESTONE] Game ended with unknown result — skipped");
            return;
        }

        Set<String> invokedAdvisors = invocationCounter.snapshot();
        if (invokedAdvisors.isEmpty()) {
            log.infof("[ADVISORY-MILESTONE] No advisors invoked this game — skipped");
            return;
        }

        AttestationVerdict verdict = switch (event.result()) {
            case WIN -> AttestationVerdict.ENDORSED;
            case LOSS -> AttestationVerdict.CHALLENGED;
            case TIE -> AttestationVerdict.SOUND;
            case UNKNOWN -> throw new AssertionError("unreachable — guarded above");
        };

        for (String advisorId : invokedAdvisors) {
            var existingEntry = advisorySession.entryId(advisorId);
            if (existingEntry.isPresent()) {
                outcomeRecorder.addAttestation(existingEntry.get(), verdict, 1.0, "game-outcome");
            } else {
                outcomeRecorder.record(OutcomeRecord.of(
                    advisorId, gameSession.id(), "game-outcome", verdict, 1.0));
            }
        }

        log.infof("[ADVISORY-MILESTONE] Game-end recorded: advisors=%s result=%s verdict=%s",
            invokedAdvisors, event.result(), verdict);
    }
}
