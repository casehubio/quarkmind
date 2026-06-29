package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkmind.sc2.GameStarted;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.annotation.CaseType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the L2 moment event bus and the quarkmind-moments Qhorus channel.
 *
 * <p>Bridges two worlds: the in-memory {@link EventStreamBus} used by the
 * summarisation pipeline (L2-L3-L4) and the persistent Qhorus channel for
 * audit and cross-agent visibility.
 *
 * <p>Wires {@link MomentDetectionTask}'s output to this bus in {@code @PostConstruct},
 * and registers any CDI-discovered {@link MomentConsumer} implementations.
 *
 * <p>On {@link GameStarted}, notifies {@link SummarisationLifecycle} to clear its
 * accumulators and runners. Bus subscriptions persist across games — they are tied
 * to CDI lifecycle, not game lifecycle.
 *
 * <p>Refs #182
 */
@ApplicationScoped
public class MomentBroker {

    public static final String CHANNEL_NAME = "quarkmind-moments";
    private static final Logger log = Logger.getLogger(MomentBroker.class);

    private final EventStreamBus<GameMoment> momentBus = new EventStreamBus<>();

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject @Any Instance<MomentConsumer> consumers;
    @Inject @CaseType("starcraft-game") MomentDetectionTask momentDetectionTask;
    @Inject SummarisationLifecycle summarisationLifecycle;

    private UUID channelId;

    @PostConstruct
    void init() {
        // ChannelService delegates to JPA — @Transactional on @PostConstruct is not
        // intercepted by Arc, so use QuarkusTransaction.requiringNew().
        // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
        channelId = QuarkusTransaction.requiringNew().call(() ->
            channelService.findByName(CHANNEL_NAME)
                .map(c -> c.id)
                .orElseGet(() -> channelService.create(
                    new ChannelCreateRequest(
                        CHANNEL_NAME,
                        "Summarisation events (L2 moments, L3 phases, L4 arcs)",
                        ChannelSemantic.APPEND,
                        null, null, null, null, null,
                        Set.of(MessageType.STATUS),
                        null, null, null, null, null)
                ).id)
        );

        // Wire MomentDetectionTask's output to our bus
        momentDetectionTask.setMomentBus(momentBus);

        // Subscribe Qhorus dispatch — persists across games (never cleared)
        momentBus.subscribe(m -> true, this::dispatchToQhorus);
        log.infof("[MOMENT-BROKER] Channel ready: %s", channelId);
    }

    public EventStreamBus<GameMoment> momentBus() { return momentBus; }
    public UUID channelId() { return channelId; }

    /**
     * Triggers SummarisationLifecycle reset on game restart.
     *
     * <p>MomentBroker is the {@link GameStarted} coordinator for the summarisation pipeline.
     * {@link SummarisationLifecycle} does NOT observe {@code GameStarted} directly —
     * MomentBroker calls {@link SummarisationLifecycle#reset()} to clear accumulators
     * and runners.
     *
     * <p>NOTE: Does NOT clear {@code momentBus} subscriptions — they persist across games.
     * {@link MomentDetectionTask}, {@link SummarisationLifecycle}, and {@link MomentConsumer}
     * beans subscribe in {@code @PostConstruct}; clearing subscriptions would orphan the pipeline.
     */
    void onGameStarted(@Observes GameStarted event) {
        // Notify SummarisationLifecycle to clear accumulators
        summarisationLifecycle.reset();
        log.debugf("[MOMENT-BROKER] Lifecycle reset for new game");
    }


    private void dispatchToQhorus(LevelEvent<GameMoment> event) {
        try {
            String content = objectMapper.writeValueAsString(
                Map.of("level", event.level().ordinal(),
                       "type", event.payload().type().name(),
                       "frame", event.payload().gameFrame(),
                       "context", event.payload().context()));
            messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender("summarisation.moment-broker")
                .actorType(ActorType.AGENT)
                .type(MessageType.STATUS)
                .content(content)
                .build());
        } catch (Exception e) {
            // Qhorus dispatch is best-effort — must not block the in-memory pipeline.
            // JsonProcessingException on serialisation, or transaction/persistence errors
            // from MessageService are logged but never propagated.
            log.warnf("Failed to dispatch moment to Qhorus: %s", e.getMessage());
        }
    }
}
