package io.quarkmind.plugin.coaching;

public record OpenCommitment(
        String correlationId,
        String agentId,
        CoachingAdvice advice,
        long issuedAtFrame,
        io.quarkmind.domain.GameState baselineState
) {}
