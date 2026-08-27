package io.quarkmind.qa.workbench;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

@UnlessBuildProfile("prod")
@WebSocket(path = "/ws/workbench")
public class WorkbenchSocket {

    @Inject
    WorkbenchBroadcaster broadcaster;


    @Inject
    com.fasterxml.jackson.databind.ObjectMapper            objectMapper;
    @Inject
    CoachingAcknowledgmentHandler                          acknowledgmentHandler;
    @Inject
    io.quarkmind.plugin.commentary.CommentaryChannelBroker commentaryChannelBroker;
    @Inject
    io.casehub.qhorus.runtime.message.MessageService       messageService;


    @io.smallrye.common.annotation.Blocking
    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        sendCommentaryHistory(connection);
        broadcaster.addSession(connection);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        broadcaster.removeSession(connection);
    }

    @io.quarkus.websockets.next.OnTextMessage
    public void onMessage(String message, WebSocketConnection connection) {
        try {
            var    node = objectMapper.readTree(message);
            String type = node.path("type").asText(null);
            if (!"coaching_response".equals(type)) {return;}

            String correlationId = node.path("correlationId").asText(null);
            String response      = node.path("response").asText(null);
            if (correlationId == null || correlationId.isBlank()) {return;}
            if (!"DONE".equals(response) && !"DECLINE".equals(response)) {return;}

            acknowledgmentHandler.acknowledge(correlationId, "DONE".equals(response));
        } catch (Exception e) {
            // malformed message — ignore silently
        }
    }

    private void sendCommentaryHistory(WebSocketConnection connection) {
        java.util.UUID channelId = commentaryChannelBroker.channelId();
        if (channelId == null) {return;}
        try {
            var all    = messageService.history(channelId, 0L, 500);
            var recent = all.size() > 100 ? all.subList(all.size() - 100, all.size()) : all;
            for (var msg : recent) {
                try {
                    var completed = objectMapper.readValue(msg.content(),
                                                           io.quarkmind.plugin.commentary.CommentaryCompleted.class);
                    var event = new WorkbenchEvent("commentary_snapshot",
                                                   new CommentaryPayload(completed.text(), completed.capability(),
                                                                         completed.commentaryType().name(), completed.gameFrame(),
                                                                         completed.workerId(), completed.latencyMs(), msg.createdAt()));
                    connection.sendText(objectMapper.writeValueAsString(event))
                              .subscribe().with(ignored -> {}, err -> {});
                } catch (Exception e) {
                    // skip malformed messages
                }
            }
        } catch (Exception e) {
            // channel not yet initialized — skip history
        }
    }


}
