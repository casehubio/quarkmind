package io.quarkmind.sc2;

public record GameStarted(String opponentRace, String opponentType,
                          String opponentDifficulty, String opponentPlayerId) {
    public GameStarted() {
        this("UNKNOWN", "UNKNOWN", null, null);
    }
}
