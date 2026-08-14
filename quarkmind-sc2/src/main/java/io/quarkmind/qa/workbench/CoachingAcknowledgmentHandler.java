package io.quarkmind.qa.workbench;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.plugin.coaching.CoachingChannelBroker;
import io.quarkmind.plugin.coaching.CoachingComplianceEvaluator;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@UnlessBuildProfile("prod")
@ApplicationScoped
public class CoachingAcknowledgmentHandler {

    private static final Logger log = Logger.getLogger(CoachingAcknowledgmentHandler.class);

    private final CoachingComplianceEvaluator evaluator;
    private final CoachingChannelBroker broker;
    private final MessageService messageService;

    @Inject
    CoachingAcknowledgmentHandler(CoachingComplianceEvaluator evaluator,
                                   CoachingChannelBroker broker,
                                   MessageService messageService) {
        this.evaluator = evaluator;
        this.broker = broker;
        this.messageService = messageService;
    }

    public boolean acknowledge(String correlationId, boolean accepted) {
        boolean resolved = evaluator.resolveHuman(correlationId, accepted);
        if (!resolved) {
            log.debugf("[COACHING-ACK] correlationId=%s not found", correlationId);
            return false;
        }

        if (messageService != null && broker != null && broker.channelId() != null) {
            try {
                messageService.dispatch(
                    MessageDispatch.builder()
                        .channelId(broker.channelId())
                        .sender("coaching.acknowledgment-handler")
                        .type(accepted ? MessageType.DONE : MessageType.DECLINE)
                        .content(accepted ? "Human accepted coaching advice" : "Human declined coaching advice")
                        .correlationId(correlationId)
                        .actorType(ActorType.HUMAN)
                        .build());
            } catch (Exception e) {
                log.warnf(e, "[COACHING-ACK] MessageService dispatch failed: %s", e.getMessage());
            }
        }

        return true;
    }
}
