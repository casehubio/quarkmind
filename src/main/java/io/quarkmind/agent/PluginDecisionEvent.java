package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import java.util.UUID;

/**
 * CDI event fired by plugins on state transitions.
 * Consumed by PluginOutcomeAuditor (@ObservesAsync) to write a ledger OutcomeRecord.
 * Only fired on transitions — not every tick.
 */
public record PluginDecisionEvent(
        String actorId,             // plugin.getId()
        String capabilityTag,       // QuarkMindCapabilityTag constant
        AttestationVerdict verdict, // SOUND (normal) or FLAGGED (plugin error)
        UUID gameSessionId,
        int gameFrame               // logged by observer for tracing; 0 when unavailable
) {}
