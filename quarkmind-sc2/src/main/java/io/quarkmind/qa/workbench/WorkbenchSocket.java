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

    @Inject WorkbenchBroadcaster broadcaster;


    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Inject
    CoachingAcknowledgmentHandler               acknowledgmentHandler;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
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

}
