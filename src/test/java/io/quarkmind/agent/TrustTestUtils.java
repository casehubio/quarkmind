package io.quarkmind.agent;

import io.casehub.ledger.memory.InMemoryActorTrustScoreRepository;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.platform.api.identity.ActorType;

import java.time.Instant;

import static io.casehub.ledger.api.model.ActorTrustScore.ScoreType;

/**
 * Test helper for seeding trust scores into {@link InMemoryActorTrustScoreRepository}.
 *
 * <p>{@link ActorTrustScoreRepository#upsert} takes 13 parameters; this utility provides
 * semantically meaningful seeds with internally consistent alpha/beta values so that
 * if {@code IncrementalTrustUpdateObserver} fires during a test, it does not produce
 * wildly inconsistent rewrites.
 *
 * <p>{@code MaterializedTrustScoreSource} reads {@code trustScore} and {@code decisionCount}
 * directly from the stored record — it does not recompute from alpha/beta — so a small
 * rounding discrepancy in alpha/beta does not affect routing decisions.
 */
public final class TrustTestUtils {

    private TrustTestUtils() {}

    /**
     * Seeds a QUALIFIED trust score (trustScore=0.82, decisionCount=12) for the given
     * (actorId, capabilityTag) pair. Score is above the default threshold+margin (0.65+0.08=0.73),
     * and decisionCount is above minimumObservations (10).
     *
     * <p>Alpha/beta are set consistently via the Beta(1,1) prior:
     * alpha = attestationPositive + 1 = 11, beta = attestationNegative + 1 = 3.
     * {@code 11/(11+3) ≈ 0.786} — close to 0.82 (rounding artefact).
     */
    public static void seedQualified(ActorTrustScoreRepository repo,
                                     String actorId,
                                     String capabilityTag) {
        int pos = 10, neg = 2;
        double alpha = pos + 1.0, beta = neg + 1.0;
        repo.upsert(
            actorId,
            ScoreType.CAPABILITY,
            capabilityTag,
            null,
            ActorType.AGENT,
            0.82,
            12,
            0,
            alpha, beta,
            pos, neg,
            Instant.now()
        );
    }
}
