package io.quarkmind.domain;

import java.util.List;

public record ScoutingObservationSequence(
    String replayHash,
    int playerId,
    Race playerRace,
    Race opponentRace,
    List<ScoutingObservation> observations,
    String groundTruthStrategy
) {}
