package io.quarkmind.ville.protocol;

import java.util.List;

public sealed interface VilleServerMessage {

    record Perception(VillePerception perception) implements VilleServerMessage {}

    record ObserverPerception(long tick, List<CharacterSnapshot> characters,
                              List<VilleEvent> events) implements VilleServerMessage {}

    record Result(String intentId, boolean success, String message) implements VilleServerMessage {}

    record ThoughtBroadcast(String characterId, String thinking, long tick) implements VilleServerMessage {}
}
