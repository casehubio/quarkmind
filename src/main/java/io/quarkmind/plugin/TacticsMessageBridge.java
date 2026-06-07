package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.TacticsTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CDI bridge that makes {@link DroolsTacticsTask}'s {@link MessageObserver}
 * implementation discoverable by qhorus's {@code MessageService}.
 *
 * <p>The problem: {@code MessageService} injects {@code Instance<MessageObserver>}
 * without {@code @Any}. CDI's default behaviour only discovers beans with
 * {@code @Default} qualifier.  {@code DroolsTacticsTask} carries
 * {@code @CaseType("starcraft-game")} — a CDI qualifier — which removes the
 * implicit {@code @Default}, making it invisible to the unqualified lookup.
 *
 * <p>This bridge bean has no custom qualifier, so it gets {@code @Default} and
 * is discovered by {@code Instance<MessageObserver>}.  It delegates to the
 * real {@code DroolsTacticsTask} via its CDI proxy.
 */
@ApplicationScoped
public class TacticsMessageBridge implements MessageObserver {

    @Inject @CaseType("starcraft-game")
    TacticsTask tacticsTask;

    @Override
    public Set<String> channels() {
        return Set.of(ScoutingIntelBroker.CHANNEL_NAME);
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        // DroolsTacticsTask implements MessageObserver; delegate directly.
        ((MessageObserver) tacticsTask).onMessage(event);
    }
}
