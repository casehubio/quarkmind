package io.quarkmind.ville.protocol;

public sealed interface VilleClientMessage {

    record Connect(String role, String characterId) implements VilleClientMessage {}

    record IntentMessage(String intentId, VilleIntent intent) implements VilleClientMessage {}

    record Thought(String thinking) implements VilleClientMessage {}
}
