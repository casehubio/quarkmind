package io.quarkmind.plugin.commentary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Owns the quarkmind-commentary qhorus channel — commentary audit trail.
 *
 * <p>Observes {@link CommentaryCompleted} events and dispatches STATUS messages with
 * JSON-serialized commentary payload to the channel.
 *
 * <p>Channel: {@code ChannelSemantic.APPEND}, {@code Set.of(MessageType.STATUS)}
 * Sender: {@code "commentary.reactive"} or {@code "commentary.narrative"} (derived from event type)
 * Actor type: {@code AGENT}
 *
 * <p>Refs #181
 */
@ApplicationScoped
public class CommentaryChannelBroker {

    public static final String CHANNEL_NAME = "quarkmind-commentary";

    private static final Logger log = Logger.getLogger(CommentaryChannelBroker.class);

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
                                                                                                             .description("Commentary audit trail")
                                                                                                             .semantic(ChannelSemantic.APPEND)
                                                                                                             .allowedTypes(Set.of(MessageType.STATUS))
                                                                                                             .build()
                                                                                                                       ).id())
                                                          );
        log.infof("[COMMENTARY-BROKER] Channel ready: %s", channelId);}

    public UUID channelId() { return channelId; }

    /**
     * Observes {@link CommentaryCompleted} events and dispatches STATUS message with commentary JSON.
     *
     * <p>Called from CDI event bus after a commentary Worker completes.
     * Runs in the same transaction as the commentary Worker's CaseContext writes.
     */
    void onCommentaryCompleted(@Observes CommentaryCompleted event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.errorf(e, "[COMMENTARY-BROKER] Failed to serialize CommentaryCompleted: %s", event);
            return;
        }

        String sender = "commentary." + event.commentaryType().name().toLowerCase();

        messageService.dispatch(
            MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(MessageType.STATUS)
                .content(payload)
                .actorType(ActorType.AGENT)
                .build()
        );

        log.debugf("[COMMENTARY-BROKER] Dispatched commentary: worker=%s, capability=%s, type=%s, frame=%d",
            event.workerId(), event.capability(), event.commentaryType(), event.gameFrame());
    }
}
