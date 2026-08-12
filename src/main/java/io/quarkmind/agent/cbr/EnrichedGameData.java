package io.quarkmind.agent.cbr;

import java.util.List;
import java.util.OptionalDouble;

public record EnrichedGameData(
        List<String> phaseSequence,
        int momentCount,
        String arcNarrative,
        double gameDurationMinutes,
        int battleCount,
        double dominanceArmy,
        double dominanceOverall,
        int expansionCount,
        int workerCountFinal,
        double dominanceEconomy,
        int supplyBlockCount,
        OptionalDouble firstContactMinute,
        OptionalDouble scoutDispatchMinute,
        double archetypeConfidence,
        String opponentId,
        int engagementsWon,
        int engagementsLost,
        double unitTradeRatio,
        double scoutingConvergence,
        boolean assessmentStable
) {
    public EnrichedGameData {
        phaseSequence = List.copyOf(phaseSequence);
    }
}
