package io.quarkmind.ville.server;

import io.quarkmind.ville.protocol.CharacterSnapshot;
import io.quarkmind.ville.protocol.VillePerception;
import io.quarkmind.ville.protocol.VilleServerMessage;
import java.util.List;
import java.util.Map;

public class PerceptionBuilder {

    public static VillePerception forAgent(String characterId, WorldState world, double range) {
        var character = world.character(characterId);
        var self = snapshot(character);
        var nearby = world.characters().stream()
                .filter(c -> !c.id().equals(characterId) && c.isConnected())
                .filter(c -> character.position().distanceTo(c.position()) <= range)
                .map(PerceptionBuilder::snapshot)
                .toList();
        return new VillePerception(world.tick(), self, nearby, List.of());
    }

    public static VilleServerMessage.ObserverPerception forObserver(WorldState world) {
        var characters = world.characters().stream()
                .filter(CharacterState::isConnected)
                .map(PerceptionBuilder::snapshot)
                .toList();
        return new VilleServerMessage.ObserverPerception(world.tick(), characters, List.of());
    }

    private static CharacterSnapshot snapshot(CharacterState c) {
        return new CharacterSnapshot(
                c.id(), c.position(),
                Map.of("SOCIAL", c.needState().get("SOCIAL"),
                       "ENERGY", c.needState().get("ENERGY")),
                c.lastDialogue());
    }
}
