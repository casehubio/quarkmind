package io.quarkmind.agent;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;

/**
 * Maps game context (enemy posture, game phase) to preferred disposition axis values.
 *
 * <p>Computes a soft multiplier (0.8–1.2) that adjusts a candidate's trust score based on
 * how well their declared disposition aligns with the current game context. Two axes are
 * evaluated: {@code riskAppetite} and {@code ruleFollowing}. Each matching axis contributes
 * +0.1; each mismatching axis contributes -0.1. Null preferences (no opinion) are neutral.
 *
 * <p>The multiplier range is intentionally narrow — disposition fit is a soft preference
 * that adjusts candidate ordering without ever hard-excluding a candidate. A high-trust
 * advisor with the "wrong" disposition can still be selected.
 *
 * @param preferredRiskAppetite preferred value for the riskAppetite axis (e.g. "conservative",
 *                              "bold"); null means no preference on this axis
 * @param preferredRuleFollowing preferred value for the ruleFollowing axis (e.g. "strict",
 *                               "flexible"); null means no preference on this axis
 */
public record DispositionPreference(String preferredRiskAppetite, String preferredRuleFollowing) {

    /** Neutral preference — no game-context signal on either axis. */
    static final DispositionPreference NEUTRAL = new DispositionPreference(null, null);

    /**
     * Compute the disposition multiplier for the given agent disposition.
     *
     * <p>Each preference axis contributes ±0.1 to the base multiplier of 1.0:
     * <ul>
     *   <li>Axis matches → +0.1</li>
     *   <li>Axis mismatches → -0.1</li>
     *   <li>Axis preference is null (no opinion) → 0.0</li>
     *   <li>Agent disposition value is null → 0.0 (cannot match or mismatch)</li>
     * </ul>
     *
     * @param disposition the agent's declared disposition; null-safe — returns 1.0 for null
     * @return multiplier in [0.8, 1.2]
     */
    public double computeMultiplier(final AgentDisposition disposition) {
        if (disposition == null) {
            return 1.0;
        }

        double adjustment = 0.0;
        adjustment += axisContribution(preferredRiskAppetite, disposition.primaryTerm(DispositionAxis.RISK_APPETITE));
        adjustment += axisContribution(preferredRuleFollowing, disposition.primaryTerm(DispositionAxis.RULE_FOLLOWING));
        return 1.0 + adjustment;}

    /**
     * Returns +0.1 if the axis value matches the preference, -0.1 if it mismatches,
     * or 0.0 if either side has no opinion.
     */
    private static double axisContribution(final String preferred, final String actual) {
        if (preferred == null || actual == null) {
            return 0.0;
        }
        return preferred.equals(actual) ? 0.1 : -0.1;
    }
}
