package io.quarkmind.ville.server;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import jakarta.json.Json;
import org.jboss.logging.Logger;

import java.io.StringReader;

@WebSocket(path = "/ws/ville")
public class VilleSocket {

    private static final Logger log = Logger.getLogger(VilleSocket.class);

    @Inject VilleServer server;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        log.debug("[VILLE] WebSocket connection opened");
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection connection) {
        try {
            var json = Json.createReader(new StringReader(message)).readObject();
            String type = json.getString("type", null);
            if (type == null) return;

            switch (type) {
                case "CONNECT" -> {
                    String role = json.getString("role", "agent");
                    String characterId = json.getString("characterId", null);
                    server.handleConnect(connection, role, characterId);
                }
                case "INTENT" -> server.handleIntent(connection, json);
                case "THOUGHT" -> server.handleThought(connection, json.getString("thinking", ""));
            }
        } catch (Exception e) {
            log.warnf("[VILLE] Failed to parse message: %s", e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        server.handleDisconnect(connection);
    }
}
