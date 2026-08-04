package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;

public enum CoachingStyle {
    COMMANDER,
    RALLY,
    INSTRUCTOR,
    MENTOR;

    public static CoachingStyle resolve(AgentDisposition disposition, CoachingUrgencyTier tier) {
        boolean bold = tier == CoachingUrgencyTier.CRISIS
                    || tier == CoachingUrgencyTier.STRATEGIC
                    || (disposition != null && "bold".equals(disposition.primaryTerm(DispositionAxis.RISK_APPETITE)));
        boolean independent = tier == CoachingUrgencyTier.CRISIS
                           || (disposition != null && "independent".equals(disposition.primaryTerm(DispositionAxis.SOCIAL_ORIENTATION)));

        if (bold && independent) return COMMANDER;
        if (bold)                return RALLY;
        if (independent)         return INSTRUCTOR;
        return MENTOR;
    }
}
