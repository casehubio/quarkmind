package io.quarkmind.ville.server;

import io.quarkmind.ville.protocol.Position;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldState {

    private final Map<String, CharacterState> characters = new ConcurrentHashMap<>();
    private long tick;

    public void addCharacter(String id, Position position) {
        characters.put(id, new CharacterState(id, position));
    }

    public CharacterState character(String id) {
        return characters.get(id);
    }

    public Collection<CharacterState> characters() {
        return characters.values();
    }

    public long tick() { return tick; }

    public void incrementTick() { tick++; }
}
