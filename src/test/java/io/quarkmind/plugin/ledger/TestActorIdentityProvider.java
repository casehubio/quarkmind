package io.quarkmind.plugin.ledger;

import io.casehub.ledger.runtime.privacy.ActorIdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Test-only ActorIdentityProvider stub that satisfies CDI without requiring EntityManager.
 * Replaces the @DefaultBean produced by LedgerPrivacyProducer (which injects EntityManager,
 * unavailable when %test.quarkus.hibernate-orm.enabled=false).
 * Pass-through: returns raw actor identifiers unchanged.
 */
@ApplicationScoped
public class TestActorIdentityProvider implements ActorIdentityProvider {

    @Override
    public String tokenise(String rawActorId) {
        return rawActorId;
    }

    @Override
    public String tokeniseForQuery(String rawActorId) {
        return rawActorId;
    }

    @Override
    public Optional<String> resolve(String token) {
        return Optional.ofNullable(token);
    }

    @Override
    public void erase(String rawActorId) {
        // pass-through: no mapping to sever
    }
}
