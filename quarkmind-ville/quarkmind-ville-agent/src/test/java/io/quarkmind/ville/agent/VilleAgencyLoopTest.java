package io.quarkmind.ville.agent;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.ville.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class VilleAgencyLoopTest {

    @Test
    void tickPopulatesNeedsFromPerception() {
        var needState = new NeedState();
        var context = new AgencyContext(needState);
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 42.0, "ENERGY", 88.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop((sys, usr, id) -> "{\"action\":\"REST\"}");
        loop.tick(context);

        assertThat(needState.get("SOCIAL")).isEqualTo(42.0);
        assertThat(needState.get("ENERGY")).isEqualTo(88.0);
    }

    @Test
    void tickSetsTickFromPerception() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(7,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop((sys, usr, id) -> "{\"action\":\"REST\"}");
        loop.tick(context);

        assertThat(context.tick()).isEqualTo(7);
    }

    @Test
    void tickProducesMoveIntent() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop(
                (sys, usr, id) -> "{\"action\":\"MOVE\",\"x\":10.0,\"y\":20.0,\"z\":0.0}");
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<VilleIntent>) context.get("intents");
        assertThat(intents).hasSize(1);
        assertThat(intents.get(0)).isInstanceOf(VilleIntent.Move.class);
        var move = (VilleIntent.Move) intents.get(0);
        assertThat(move.target()).isEqualTo(new Position(10.0, 20.0, 0.0));
    }

    @Test
    void tickProducesTalkIntent() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop(
                (sys, usr, id) -> "{\"action\":\"TALK\",\"text\":\"Hello!\"}");
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<VilleIntent>) context.get("intents");
        assertThat(intents).hasSize(1);
        assertThat(intents.get(0)).isInstanceOf(VilleIntent.Talk.class);
        assertThat(((VilleIntent.Talk) intents.get(0)).text()).isEqualTo("Hello!");
    }

    @Test
    void tickProducesRestIntent() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop((sys, usr, id) -> "{\"action\":\"REST\"}");
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<VilleIntent>) context.get("intents");
        assertThat(intents).hasSize(1);
        assertThat(intents.get(0)).isInstanceOf(VilleIntent.Rest.class);
    }

    @Test
    void tickExtractsThinking() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop(
                (sys, usr, id) -> "{\"thinking\":\"I should rest\",\"action\":\"REST\"}");
        loop.tick(context);

        assertThat(context.get("thinking")).isEqualTo("I should rest");
    }

    @Test
    void tickSkipsWhenNoPerception() {
        var context = new AgencyContext(new NeedState());
        var loop = new VilleAgencyLoop((sys, usr, id) -> { throw new AssertionError("should not call"); });
        loop.tick(context);
        assertThat(context.get("intents")).isNull();
    }

    @Test
    void malformedLlmResponseProducesEmptyIntents() {
        var context = new AgencyContext(new NeedState());
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());
        context.put("perception", perception);

        var loop = new VilleAgencyLoop((sys, usr, id) -> "not json at all");
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<VilleIntent>) context.get("intents");
        assertThat(intents).isEmpty();
    }

    @Test
    void promptIncludesNearbyCharacters() {
        var context = new AgencyContext(new NeedState());
        var bob = new CharacterSnapshot("bob", new Position(3, 0, 0),
                Map.of("SOCIAL", 80.0), "Hello!");
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(bob), List.of());
        context.put("perception", perception);

        var captured = new String[1];
        var loop = new VilleAgencyLoop((sys, usr, id) -> {
            captured[0] = usr;
            return "{\"action\":\"REST\"}";
        });
        loop.tick(context);

        assertThat(captured[0]).contains("bob");
        assertThat(captured[0]).contains("Hello!");
    }
}
