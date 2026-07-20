package io.quarkmind.plugin.coaching;

public record CoachingAdvice(
        String advice,
        CoachingDomain domainTag,
        VerificationPredicate verification,
        int verificationWindowFrames
) {
    private static final int MIN_WINDOW_FRAMES = 200;

    public CoachingAdvice {
        verificationWindowFrames = Math.max(verificationWindowFrames, MIN_WINDOW_FRAMES);
    }

    public boolean isVerifiable() {
        return verification != null;
    }
}
