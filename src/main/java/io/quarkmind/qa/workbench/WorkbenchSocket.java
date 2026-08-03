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

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        broadcaster.addSession(connection);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        broadcaster.removeSession(connection);
    }
}
