package io.quarkmind.ville.server;

import io.quarkmind.ville.protocol.Position;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorldStateTest {

    @Test
    void addAndRetrieveCharacter() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(10, 20, 0));
        assertThat(world.character("alice")).isNotNull();
        assertThat(world.character("alice").position()).isEqualTo(new Position(10, 20, 0));
    }

    @Test
    void charactersReturnsAllActive() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(5, 5, 0));
        assertThat(world.characters()).hasSize(2);
    }

    @Test
    void tickCounterIncrements() {
        var world = new WorldState();
        assertThat(world.tick()).isEqualTo(0);
        world.incrementTick();
        assertThat(world.tick()).isEqualTo(1);
    }
}
