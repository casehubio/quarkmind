package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class GamePhaseSummariser implements Summariser<GameMoment, TacticalPosture> {

    private static final Set<GameMomentType> COMBAT_TYPES = Set.of(
        GameMomentType.BATTLE_STARTED, GameMomentType.BATTLE_ENDED,
        GameMomentType.NEXUS_UNDER_ATTACK);

    @Override
    public CompletionStage<List<TacticalPosture>> summarise(List<LevelEvent<GameMoment>> batch) {
        return CompletableFuture.completedFuture(doSummarise(batch));
    }

    private List<TacticalPosture> doSummarise(List<LevelEvent<GameMoment>> batch) {
        if (batch.isEmpty()) return List.of();

        long latestFrame = batch.get(batch.size() - 1).timestamp();
        long combatCount = batch.stream()
            .filter(e -> COMBAT_TYPES.contains(e.payload().type()))
            .count();
        boolean hasNexusAttack = batch.stream()
            .anyMatch(e -> e.payload().type() == GameMomentType.NEXUS_UNDER_ATTACK);
        boolean hasEconomicCrisis = batch.stream()
            .anyMatch(e -> e.payload().type() == GameMomentType.ECONOMIC_CRISIS);

        String phase;
        String rationale;

        if (hasNexusAttack) {
            phase = "DEFENSIVE_HOLD";
            rationale = "Base under direct attack";
        } else if (hasEconomicCrisis && combatCount > 0) {
            phase = "EARLY_AGGRESSION";
            rationale = "Enemy all-in with combat engagement";
        } else if (combatCount >= 2) {
            phase = "MID_SKIRMISH";
            rationale = combatCount + " combat events in window";
        } else if (combatCount == 1) {
            phase = "TRANSITIONING";
            rationale = "Single combat event — phase uncertain";
        } else {
            phase = "EARLY_MACRO";
            rationale = "No combat — economic development";
        }

        return List.of(new TacticalPosture(phase, latestFrame, rationale));
    }
}
