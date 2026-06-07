package io.quarkmind.agent;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ScoutingIntelBroker {

    public static final String CHANNEL_NAME = "quarkmind-scouting-intel";

    // @Any is required: DroolsTacticsTask has @CaseType("starcraft-game") qualifier.
    // Without @Any, CDI Instance<> only discovers @Default beans and misses qualified ones.
    @Inject @Any Instance<ScoutingIntelConsumer> consumers;
    @Inject ChannelService channelService;

    private UUID channelId;
    private Set<ScoutingIntelType> activeTypes;

    @PostConstruct
    void init() {
        // ChannelService delegates to JPA (JpaChannelStore) which requires a transaction.
        // @Transactional on @PostConstruct is not intercepted by Arc during bean creation,
        // so we use QuarkusTransaction.requiringNew() to get an explicit transaction.
        // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
        channelId = QuarkusTransaction.requiringNew().call(() ->
            channelService.findByName(CHANNEL_NAME)
                .map(c -> c.id)
                .orElseGet(() -> channelService.create(
                    CHANNEL_NAME,
                    "Scouting intel for agent plugins",
                    ChannelSemantic.APPEND,
                    null, null, null, null, null,
                    "STATUS"   // allowedTypes — STATUS carries content; EVENT forces null (GE-20260607-d051f2)
                ).id)
        );

        // qhorus#254: ChannelService.create() does NOT call channelGateway.initChannel();
        // ChannelBackend registration never fires for runtime-created channels.
        // MessageObserver with channels() filter is the correct delivery path.

        activeTypes = computeActiveTypes(consumers);
    }

    // Extracted as package-private static to test subscription union logic without CDI Instance<>
    static Set<ScoutingIntelType> computeActiveTypes(Iterable<ScoutingIntelConsumer> consumers) {
        Set<ScoutingIntelType> result = new HashSet<>();
        for (ScoutingIntelConsumer c : consumers) {
            result.addAll(c.subscribedIntelTypes());
        }
        return Set.copyOf(result);
    }

    public UUID channelId()                          { return channelId; }
    public boolean isSubscribed(ScoutingIntelType t) { return activeTypes.contains(t); }
    public Set<ScoutingIntelType> activeTypes()      { return activeTypes; }
}
