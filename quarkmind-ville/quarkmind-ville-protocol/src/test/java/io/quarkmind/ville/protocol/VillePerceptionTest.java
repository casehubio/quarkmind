package io.quarkmind.ville.protocol;

import io.quarkmind.agency.spi.WorldPerception;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class VillePerceptionTest {

    @Test
    void perceptionImplementsWorldPerception() {
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0), Map.of(), null),
                List.of(), List.of());
        assertThat(perception).isInstanceOf(WorldPerception.class);
    }

    @Test
    void perceptionContainsSelfAndNearby() {
        var self = new CharacterSnapshot("alice", new Position(10, 20, 0),
                Map.of("SOCIAL", 45.0, "ENERGY", 72.0), null);
        var bob = new CharacterSnapshot("bob", new Position(12, 21, 0),
                Map.of("SOCIAL", 80.0, "ENERGY", 30.0), "Hello");
        var perception = new VillePerception(42, self, List.of(bob), List.of());

        assertThat(perception.tick()).isEqualTo(42);
        assertThat(perception.self().id()).isEqualTo("alice");
        assertThat(perception.nearby()).hasSize(1);
        assertThat(perception.nearby().get(0).lastDialogue()).isEqualTo("Hello");
    }

    @Test
    void positionDistanceCalculation() {
        var a = new Position(0, 0, 0);
        var b = new Position(3, 4, 0);
        assertThat(a.distanceTo(b)).isCloseTo(5.0, within(0.01));
    }

    @Test
    void observerPerceptionContainsAllCharacters() {
        var alice = new CharacterSnapshot("alice", new Position(10, 20, 0),
                Map.of("SOCIAL", 45.0), null);
        var bob = new CharacterSnapshot("bob", new Position(12, 21, 0),
                Map.of("SOCIAL", 80.0), null);
        var obs = new VilleServerMessage.ObserverPerception(42, List.of(alice, bob), List.of());

        assertThat(obs.characters()).hasSize(2);
    }

    @Test
    void clientMessageDirectionality() {
        var connect = new VilleClientMessage.Connect("agent", "alice");
        assertThat(connect).isInstanceOf(VilleClientMessage.class);
        assertThat(connect.role()).isEqualTo("agent");
    }

    @Test
    void serverMessageDirectionality() {
        var result = new VilleServerMessage.Result("id-1", true, "OK");
        assertThat(result).isInstanceOf(VilleServerMessage.class);
        assertThat(result.success()).isTrue();
    }
}
