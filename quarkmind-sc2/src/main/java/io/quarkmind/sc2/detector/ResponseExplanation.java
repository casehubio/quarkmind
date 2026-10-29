package io.quarkmind.sc2.detector;

import java.util.List;
import java.util.Map;

public record ResponseExplanation(
    OpponentRationale opponent,
    ResponseRationale response,
    String expertOverride,
    ContextEffect context
) {

    public record OpponentRationale(
        String archetype,
        double weight,
        List<EvidenceContribution> keyEvidence
    ) {}

    public record ResponseRationale(
        String archetype,
        double confidence,
        double cptValue
    ) {}

    public record ContextEffect(
        Map<String, String> state,
        boolean shifted
    ) {}

    public String format() {
        var sb = new StringBuilder();
        sb.append(String.format("Response: %s (%.2f)%n", response.archetype, response.confidence));
        sb.append(String.format("  Opponent: %s (%.2f weight)%n", opponent.archetype, opponent.weight));
        if (!opponent.keyEvidence.isEmpty()) {
            sb.append("    Key evidence: ");
            for (int i = 0; i < opponent.keyEvidence.size(); i++) {
                if (i > 0) {sb.append(", ");}
                var ev = opponent.keyEvidence.get(i);
                sb.append(String.format("%s (%+.2f)", ev.variable(), ev.influence()));
            }
            sb.append('\n');
        }
        sb.append(String.format("  Response CPT: P(%s | %s) = %.2f%n",
                                response.archetype, opponent.archetype, response.cptValue));
        sb.append(String.format("  Expert override: %s%n",
                                expertOverride != null ? expertOverride : "none active"));
        sb.append(String.format("  Context: %s (%s)",
                                context.state.entrySet().stream()
                                             .sorted(java.util.Map.Entry.comparingByKey())
                                             .map(e -> e.getKey().replace("our_", "").replace("_state", "") + "=" + e.getValue())
                                             .reduce((a, b) -> a + ", " + b).orElse("none"),
                                context.shifted ? "shifted" : "no effect"));
        return sb.toString();
    }

}
