package io.quarkmind.plugin.advisory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.agent.AdvisoryCompleted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Owns the quarkmind-advisory qhorus channel — advisory audit trail.
 *
 * <p>Observes {@link AdvisoryCompleted} events and dispatches STATUS messages with
 * JSON-serialized advisory payload to the channel.
 *
 * <p>Channel: {@code ChannelSemantic.APPEND}, {@code Set.of(MessageType.STATUS)}
 * Sender: {@code "summarisation.advisory-broker"} (follows existing naming convention)
 * Actor type: {@code AGENT}
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisoryChannelBroker {

    public static final String CHANNEL_NAME = "quarkmind-advisory";

    private static final Logger log = Logger.getLogger(AdvisoryChannelBroker.class);

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;

    private volatile UUID channelId;

    @PostConstruct
    void init() {
        channelId = QuarkusTransaction.requiringNew().call(() ->
                                                                   channelService.findByName(CHANNEL_NAME)
                                                                                 .map(c -> c.id())
                                                                                 .orElseGet(() -> channelService.create(
                                                                                         ChannelCreateRequest.builder(CHANNEL_NAME)
                                                                                                             .description("Advisory audit trail")
                                                                                                             .semantic(ChannelSemantic.APPEND)
                                                                                                             .allowedTypes(Set.of(MessageType.STATUS))
                                                                                                             .build()
                                                                                                                       ).id())
                                                          );
        log.infof("[ADVISORY-BROKER] Channel ready: %s", channelId);}

    public UUID channelId() { return channelId; }

    /**
     * Observes {@link AdvisoryCompleted} events and dispatches STATUS message with advisory JSON.
     *
     * <p>Called from CDI event bus after an advisory Worker completes.
     * Runs in the same transaction as the advisory Worker's CaseContext writes.
     */
    void onAdvisoryCompleted(@Observes AdvisoryCompleted event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.errorf(e, "[ADVISORY-BROKER] Failed to serialize AdvisoryCompleted: %s", event);
            return;
        }

        messageService.dispatch(
            MessageDispatch.builder()
                .channelId(channelId)
                .sender("summarisation.advisory-broker")
                .type(MessageType.STATUS)
                .content(payload)
                .actorType(ActorType.AGENT)
                .build()
        );

        log.debugf("[ADVISORY-BROKER] Dispatched advisory: advisor=%s, capability=%s, frame=%d",
            event.advisorId(), event.capability(), event.gameFrame());
    }
}
