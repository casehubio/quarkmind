package io.quarkmind.ville.server;

import io.quarkmind.agency.needs.NeedDefinition;
import io.quarkmind.ville.protocol.Position;
import io.quarkmind.ville.protocol.VilleIntent;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@ApplicationScoped
public class VilleServer {

    private static final Logger log = Logger.getLogger(VilleServer.class);

    private final WorldState world = new WorldState();
    private GameTick gameTick;
    private final Map<WebSocketConnection, ClientInfo> clients = new ConcurrentHashMap<>();
    private final Set<WebSocketConnection> observers = new CopyOnWriteArraySet<>();

    @PostConstruct
    void init() {
        gameTick = new GameTick(List.of(
                new VilleNeedDefinition("SOCIAL", 1.0),
                new VilleNeedDefinition("ENERGY", 0.5)));

        world.addCharacter("alice", new Position(10, 10, 0));
        world.addCharacter("bob", new Position(30, 30, 0));
        log.info("[VILLE] Server initialised with alice and bob");
    }

    @Scheduled(every = "${ville.tick-interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        if (clients.isEmpty() && observers.isEmpty()) return;

        gameTick.execute(world, conversationRange(), movementSpeed());
        broadcastPerceptions();
    }

    public void handleConnect(WebSocketConnection connection, String role, String characterId) {
        if ("observer".equals(role)) {
            observers.add(connection);
            log.infof("[VILLE] Observer connected — %d observers", observers.size());
        } else if ("agent".equals(role) && characterId != null) {
            var character = world.character(characterId);
            if (character != null) {
                character.setConnected(true);
                clients.put(connection, new ClientInfo(role, characterId));
                log.infof("[VILLE] Agent '%s' connected — %d agents", characterId, clients.size());
            }
        }
    }

    public void handleIntent(WebSocketConnection connection, JsonObject message) {
        var info = clients.get(connection);
        if (info == null) return;

        var character = world.character(info.characterId());
        if (character == null) return;

        String action = message.getString("action", null);
        if (action == null) return;

        VilleIntent intent = switch (action.toUpperCase()) {
            case "MOVE" -> {
                var target = message.getJsonObject("target");
                if (target != null) {
                    yield new VilleIntent.Move(new Position(
                            target.getJsonNumber("x").doubleValue(),
                            target.getJsonNumber("y").doubleValue(),
                            target.getJsonNumber("z").doubleValue()));
                }
                yield null;
            }
            case "TALK" -> new VilleIntent.Talk(message.getString("text", ""));
            case "REST" -> new VilleIntent.Rest();
            case "EMOTE" -> new VilleIntent.Emote(message.getString("emote", ""));
            default -> null;
        };

        if (intent != null) {
            character.queueIntent(intent);
        }

        String intentId = message.getString("intentId", null);
        if (intentId != null) {
            sendResult(connection, intentId, true, "Queued");
        }
    }

    public void handleThought(WebSocketConnection connection, String thinking) {
        var info = clients.get(connection);
        if (info == null) return;

        var json = Json.createObjectBuilder()
                .add("type", "THOUGHT")
                .add("characterId", info.characterId())
                .add("thinking", thinking)
                .add("tick", world.tick())
                .build();
        String text = toJsonString(json);
        observers.forEach(obs -> obs.sendText(text).subscribe().with(
                ignored -> {}, err -> log.warnf("[VILLE] Thought send failed: %s", err.getMessage())));
    }

    public void handleDisconnect(WebSocketConnection connection) {
        var info = clients.remove(connection);
        if (info != null) {
            var character = world.character(info.characterId());
            if (character != null) {
                character.setConnected(false);
            }
            log.infof("[VILLE] Agent '%s' disconnected — %d agents", info.characterId(), clients.size());
        }
        if (observers.remove(connection)) {
            log.infof("[VILLE] Observer disconnected — %d observers", observers.size());
        }
    }

    WorldState worldState() { return world; }

    private void broadcastPerceptions() {
        for (var entry : clients.entrySet()) {
            var connection = entry.getKey();
            var info = entry.getValue();
            var perception = PerceptionBuilder.forAgent(info.characterId(), world, conversationRange());
            String json = perceptionToJson(perception);
            connection.sendText(json).subscribe().with(
                    ignored -> {}, err -> log.warnf("[VILLE] Perception send failed: %s", err.getMessage()));
        }

        if (!observers.isEmpty()) {
            var obs = PerceptionBuilder.forObserver(world);
            String json = observerPerceptionToJson(obs);
            observers.forEach(c -> c.sendText(json).subscribe().with(
                    ignored -> {}, err -> log.warnf("[VILLE] Observer send failed: %s", err.getMessage())));
        }
    }

    private void sendResult(WebSocketConnection connection, String intentId, boolean success, String message) {
        var json = Json.createObjectBuilder()
                .add("type", "RESULT")
                .add("intentId", intentId)
                .add("success", success)
                .add("message", message)
                .build();
        connection.sendText(toJsonString(json)).subscribe().with(
                ignored -> {}, err -> log.warnf("[VILLE] Result send failed: %s", err.getMessage()));
    }

    private String perceptionToJson(io.quarkmind.ville.protocol.VillePerception p) {
        var selfBuilder = Json.createObjectBuilder()
                .add("id", p.self().id())
                .add("x", p.self().position().x())
                .add("y", p.self().position().y())
                .add("z", p.self().position().z());
        var needsBuilder = Json.createObjectBuilder();
        p.self().needs().forEach(needsBuilder::add);
        selfBuilder.add("needs", needsBuilder);
        if (p.self().lastDialogue() != null) {
            selfBuilder.add("lastDialogue", p.self().lastDialogue());
        }

        var nearbyArray = Json.createArrayBuilder();
        for (var n : p.nearby()) {
            var nb = Json.createObjectBuilder()
                    .add("id", n.id())
                    .add("x", n.position().x())
                    .add("y", n.position().y())
                    .add("z", n.position().z());
            var nNeeds = Json.createObjectBuilder();
            n.needs().forEach(nNeeds::add);
            nb.add("needs", nNeeds);
            if (n.lastDialogue() != null) {
                nb.add("lastDialogue", n.lastDialogue());
            }
            nearbyArray.add(nb);
        }

        var json = Json.createObjectBuilder()
                .add("type", "PERCEPTION")
                .add("tick", p.tick())
                .add("self", selfBuilder)
                .add("nearby", nearbyArray)
                .add("events", Json.createArrayBuilder())
                .build();
        return toJsonString(json);
    }

    private String observerPerceptionToJson(io.quarkmind.ville.protocol.VilleServerMessage.ObserverPerception obs) {
        var charsArray = Json.createArrayBuilder();
        for (var c : obs.characters()) {
            var cb = Json.createObjectBuilder()
                    .add("id", c.id())
                    .add("x", c.position().x())
                    .add("y", c.position().y())
                    .add("z", c.position().z());
            var nBuilder = Json.createObjectBuilder();
            c.needs().forEach(nBuilder::add);
            cb.add("needs", nBuilder);
            if (c.lastDialogue() != null) {
                cb.add("lastDialogue", c.lastDialogue());
            }
            charsArray.add(cb);
        }

        var json = Json.createObjectBuilder()
                .add("type", "PERCEPTION")
                .add("tick", obs.tick())
                .add("characters", charsArray)
                .add("events", Json.createArrayBuilder())
                .build();
        return toJsonString(json);
    }

    private double conversationRange() { return 5.0; }
    private double movementSpeed() { return 2.0; }

    private static String toJsonString(JsonObject json) {
        var writer = new StringWriter();
        try (var w = Json.createWriter(writer)) {
            w.writeObject(json);
        }
        return writer.toString();
    }

    record ClientInfo(String role, String characterId) {}
}
