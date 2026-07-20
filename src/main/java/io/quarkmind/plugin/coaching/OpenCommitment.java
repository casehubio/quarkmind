package io.quarkmind.plugin.coaching;

public record OpenCommitment(
        String correlationId,
        CoachingAdvice advice,
        long issuedAtFrame
) {}
