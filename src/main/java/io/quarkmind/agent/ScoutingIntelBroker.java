package io.quarkmind.agent;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkmind.sc2.GameStarted;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ScoutingIntelBroker {

    public static final String CHANNEL_NAME = "quarkmind-scouting-intel";

    // @Any is required: DroolsTacticsTask has @CaseType("starcraft-game") qualifier.
    // Without @Any, CDI Instance<> only discovers @Default beans and misses qualified ones.
    @Inject @Any Instance<ScoutingIntelConsumer> consumers;
    @Inject ChannelService channelService;
    @Inject PreferenceProvider preferenceProvider;

    // Typed in-memory store — synchronous game-loop writes; ConcurrentHashMap for QA endpoint reads
    private final Map<ScoutingIntelType, ScoutingIntelPayload> latest = new ConcurrentHashMap<>();

    private final EventStreamBus<ScoutingIntelPayload> level1Bus = new EventStreamBus<>();

    private volatile UUID channelId;

    // Initialized to empty set so isSubscribed() is safe before @PostConstruct fires.
    // @PostConstruct overwrites with the real subscription union from CDI consumers.
    private volatile Set<ScoutingIntelType> activeTypes = Set.of();

    @PostConstruct
    void init() {
        // Channel initialization is deferred to first access via ensureChannel().
        // Reason: engine's DefaultCaseDefinitionRegistry.registerKnownDefinitions() runs
        // on a Vert.x IO thread via ReactiveUtils.runOnSafeVertxContext(). If ScoutingIntelBroker
        // is created during that callback (transitive CDI dependency from QuarkMindCaseHub →
        // TaskDefinition plugins → ScoutingIntelBroker), the JPA call in ChannelService throws
        // BlockingOperationNotAllowedException. Lazy init avoids this because channelId() is
        // only called from the game loop (worker thread), never from the IO registration path.
        activeTypes = computeActiveTypes(consumers);
    }

    /**
     * Lazy-initializes the qhorus channel on first access.
     *
     * <p>ChannelService delegates to JPA (JpaChannelStore) which requires a worker thread.
     * Called from game-loop code, never from Vert.x IO threads.
     */
    private UUID ensureChannel() {
        UUID id = channelId;
        if (id != null) return id;
        synchronized (this) {
            if (channelId != null) return channelId;
            // ChannelService delegates to JPA (JpaChannelStore) which requires a transaction.
            // @Transactional on @PostConstruct is not intercepted by Arc during bean creation,
            // so we use QuarkusTransaction.requiringNew() to get an explicit transaction.
            // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
            channelId = QuarkusTransaction.requiringNew().call(() ->
                channelService.findByName(CHANNEL_NAME)
                    .map(c -> c.id())
                    .orElseGet(() -> channelService.create(
                        // Closes #194: ChannelService API updated — allowedTypes is now Set<MessageType>
                        new ChannelCreateRequest(
                            CHANNEL_NAME,
                            "Scouting intel for agent plugins",
                            ChannelSemantic.APPEND,
                            null, null, null, null, null,
                            // allowedTypes — STATUS carries content; EVENT forces null (GE-20260607-d051f2)
                            Set.of(MessageType.STATUS),
                            null, null, null, null, null
                        )
                    ).id())
            );
            // qhorus#254: ChannelService.create() does NOT call channelGateway.initChannel();
            // ChannelBackend registration never fires for runtime-created channels.
            // MessageObserver with channels() filter is the correct delivery path.
            return channelId;
        }
    }

    /** Called by DroolsScoutingTask when a value changes (subscribed types only). */
    public void update(ScoutingIntelPayload payload) {
        latest.put(payload.type(), payload);
    }

    /** Untyped read — returns the latest payload for the given type, or empty. */
    public Optional<ScoutingIntelPayload> current(ScoutingIntelType type) {
        return Optional.ofNullable(latest.get(type));
    }

    /** Typed read — compile-safe; no cast at call sites. */
    public <T extends ScoutingIntelPayload> Optional<T> current(ScoutingIntelType type, Class<T> clazz) {
        return Optional.ofNullable(latest.get(type))
            .filter(clazz::isInstance)
            .map(clazz::cast);
    }

    /**
     * Clears all stored intel on game restart.
     *
     * <p>NOTE: Does NOT clear {@code level1Bus} subscriptions — they are application-scoped
     * and persist across games. {@link io.quarkmind.plugin.summarisation.MomentDetectionTask}
     * subscribes in {@code @PostConstruct}; clearing subscriptions would orphan the L1→L2 pipeline.
     */
    void onGameStarted(@Observes GameStarted event) {
        latest.clear();
    }

    /**
     * Test isolation — clears all stored intel.
     * Called from {@code @BeforeEach} in {@code @QuarkusTest} classes.
     *
     * <p>NOTE: Does NOT clear {@code level1Bus} subscriptions for the same reason as
     * {@link #onGameStarted} — subscriptions are tied to CDI lifecycle, not game lifecycle.
     */
    public void clearLatest() {
        latest.clear();
    }

    /** Hot-reload subscription union (#178) — called from QA endpoint on HTTP thread. */
    public void refreshAll() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
        consumers.forEach(c -> c.refreshSubscriptions(prefs));
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

    public UUID channelId()                                 { return ensureChannel(); }
    public boolean isSubscribed(ScoutingIntelType t)        { return activeTypes.contains(t); }
    public Set<ScoutingIntelType> activeTypes()             { return activeTypes; }
    public EventStreamBus<ScoutingIntelPayload> level1Bus() { return level1Bus; }
}
