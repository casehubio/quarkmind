package io.quarkmind.ville.server;

import io.quarkmind.ville.protocol.Position;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PerceptionBuilderTest {

    @Test
    void agentPerceptionFiltersByRange() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(3, 0, 0));
        world.addCharacter("carol", new Position(50, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(true);
        world.character("carol").setConnected(true);

        var perception = PerceptionBuilder.forAgent("alice", world, 5.0);
        assertThat(perception.self().id()).isEqualTo("alice");
        assertThat(perception.nearby()).hasSize(1);
        assertThat(perception.nearby().get(0).id()).isEqualTo("bob");
    }

    @Test
    void observerPerceptionIncludesAllCharacters() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(50, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(true);

        var obs = PerceptionBuilder.forObserver(world);
        assertThat(obs.characters()).hasSize(2);
    }

    @Test
    void snapshotIncludesNeedLevels() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").needState().set("SOCIAL", 42.0);

        var perception = PerceptionBuilder.forAgent("alice", world, 5.0);
        assertThat(perception.self().needs().get("SOCIAL")).isEqualTo(42.0);
    }

    @Test
    void disconnectedCharactersExcludedFromNearby() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(3, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(false);

        var perception = PerceptionBuilder.forAgent("alice", world, 5.0);
        assertThat(perception.nearby()).isEmpty();
    }

    @Test
    void snapshotIncludesLastDialogue() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(3, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(true);
        world.character("bob").setLastDialogue("Hello!");

        var perception = PerceptionBuilder.forAgent("alice", world, 5.0);
        assertThat(perception.nearby().get(0).lastDialogue()).isEqualTo("Hello!");
    }

    @Test
    void perceptionTickMatchesWorldTick() {
        var world = new WorldState();
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.incrementTick();
        world.incrementTick();

        var perception = PerceptionBuilder.forAgent("alice", world, 5.0);
        assertThat(perception.tick()).isEqualTo(2);
    }
}
