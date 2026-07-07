package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public class GameArcSummariser implements Summariser<GamePhase, GameArc> {

    @Override
    public CompletionStage<List<GameArc>> summarise(List<LevelEvent<GamePhase>> batch) {
        return CompletableFuture.completedFuture(doSummarise(batch));
    }

    private List<GameArc> doSummarise(List<LevelEvent<GamePhase>> batch) {
        if (batch.isEmpty()) return List.of();

        long latestFrame = batch.get(batch.size() - 1).timestamp();
        String phases = batch.stream()
            .map(e -> e.payload().phase())
            .distinct()
            .collect(Collectors.joining(" → "));
        String latestPhase = batch.get(batch.size() - 1).payload().phase();
        String rationale = batch.get(batch.size() - 1).payload().rationale();

        String narrative = String.format("Game progression: %s. Currently in %s phase — %s.",
            phases, latestPhase, rationale);

        return List.of(new GameArc(narrative, latestFrame));
    }
}
