package io.quarkmind.plugin.coaching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.sc2.GameStarted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CoachingChannelBroker {

    public static final String CHANNEL_NAME = "quarkmind-coaching";

    private static final Logger log = Logger.getLogger(CoachingChannelBroker.class);

    @Inject
    ChannelService channelService;
    @Inject
    MessageService messageService;
    @Inject
    ObjectMapper   objectMapper;

    private volatile UUID                                              channelId;
    private final    ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments          = new ConcurrentHashMap<>();
    private final    ConcurrentHashMap<CoachingDomain, Long>           latestFramePerDomain = new ConcurrentHashMap<>();
    private          int                                               dispatchCount;

    @PostConstruct
    void init() {
        channelId = QuarkusTransaction.requiringNew().call(() ->
                                                                   channelService.findByName(CHANNEL_NAME)
                                                                                 .map(c -> c.id())
                                                                                 .orElseGet(() -> channelService.create(
                                                                                         ChannelCreateRequest.builder(CHANNEL_NAME)
                                                                                                             .description("Coaching COMMAND channel — actionable advice for human players")
                                                                                                             .semantic(ChannelSemantic.APPEND)
                                                                                                             .allowedTypes(Set.of(MessageType.COMMAND, MessageType.DONE, MessageType.DECLINE))
                                                                                                             .build()
                                                                                                                       ).id())
                                                          );
        log.infof("[COACHING-BROKER] Channel ready: %s", channelId);
    }

    public UUID channelId()                                                {return channelId;}

    public ConcurrentHashMap<CoachingDomain, OpenCommitment> commitments() {return commitments;}

    int dispatchCount()                                                    {return dispatchCount;}

    void onCoachingCompleted(@Observes CoachingCompleted event) {
        CoachingDomain domain    = event.advice().domainTag();
        long           gameFrame = event.gameFrame();

        Long latestFrame = latestFramePerDomain.get(domain);
        if (latestFrame != null && gameFrame <= latestFrame) {
            log.debugf("[COACHING-BROKER] Stale frame %d for domain %s (latest=%d), discarding",
                       gameFrame, domain, latestFrame);
            return;
        }
        latestFramePerDomain.put(domain, gameFrame);

        String correlationId = UUID.randomUUID().toString();
        commitments.put(domain, new OpenCommitment(correlationId, event.advice(), gameFrame, 0));
        dispatchCount++;

        if (channelId == null) {return;}

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.errorf(e, "[COACHING-BROKER] Failed to serialize CoachingCompleted: %s", event);
            return;
        }

        try {
            messageService.dispatch(
                    MessageDispatch.builder()
                                   .channelId(channelId)
                                   .sender("coaching.channel-broker")
                                   .type(MessageType.COMMAND)
                                   .content(payload)
                                   .correlationId(correlationId)
                                   .actorType(ActorType.AGENT)
                                   .build()
                                   );
            log.debugf("[COACHING-BROKER] Dispatched COMMAND: domain=%s, frame=%d, correlationId=%s",
                       domain, gameFrame, correlationId);
        } catch (Exception e) {
            log.warnf(e, "[COACHING-BROKER] Dispatch failed for domain=%s frame=%d: %s",
                      domain, gameFrame, e.getMessage());
        }
    }

    void onGameStarted(@Observes GameStarted event) {
        commitments.clear();
        latestFramePerDomain.clear();
        dispatchCount = 0;
    }
}
