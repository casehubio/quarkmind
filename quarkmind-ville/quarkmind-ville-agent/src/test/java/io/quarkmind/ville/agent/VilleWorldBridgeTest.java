package io.quarkmind.ville.agent;

import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.ville.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class VilleWorldBridgeTest {

    @Test
    void perceiveReturnsQueuedPerception() {
        var bridge = new VilleWorldBridge();
        var perception = new VillePerception(1,
                new CharacterSnapshot("alice", new Position(0, 0, 0),
                        Map.of("SOCIAL", 50.0, "ENERGY", 50.0), null),
                List.of(), List.of());

        bridge.onPerception(perception);
        var result = bridge.perceive();
        assertThat(result.tick()).isEqualTo(1);
        assertThat(result.self().id()).isEqualTo("alice");
    }

    @Test
    void dispatchSendsIntentsAndDrainsQueue() {
        var bridge = new VilleWorldBridge();
        var queue = new IntentQueue<VilleIntent>();
        queue.enqueue(new VilleIntent.Move(new Position(10, 0, 0)));
        queue.enqueue(new VilleIntent.Talk("Hello"));

        var sent = new ArrayList<String>();
        bridge.setSendFunction(sent::add);
        bridge.dispatch(queue);

        assertThat(sent).hasSize(2);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void dispatchSerializesMoveIntent() {
        var bridge = new VilleWorldBridge();
        var queue = new IntentQueue<VilleIntent>();
        queue.enqueue(new VilleIntent.Move(new Position(10.5, 20.0, 0.0)));

        var sent = new ArrayList<String>();
        bridge.setSendFunction(sent::add);
        bridge.dispatch(queue);

        assertThat(sent.get(0)).contains("MOVE");
        assertThat(sent.get(0)).contains("10.5");
    }

    @Test
    void dispatchSerializesTalkIntent() {
        var bridge = new VilleWorldBridge();
        var queue = new IntentQueue<VilleIntent>();
        queue.enqueue(new VilleIntent.Talk("Hi there"));

        var sent = new ArrayList<String>();
        bridge.setSendFunction(sent::add);
        bridge.dispatch(queue);

        assertThat(sent.get(0)).contains("TALK");
        assertThat(sent.get(0)).contains("Hi there");
    }

    @Test
    void dispatchSerializesRestIntent() {
        var bridge = new VilleWorldBridge();
        var queue = new IntentQueue<VilleIntent>();
        queue.enqueue(new VilleIntent.Rest());

        var sent = new ArrayList<String>();
        bridge.setSendFunction(sent::add);
        bridge.dispatch(queue);

        assertThat(sent.get(0)).contains("REST");
    }

    @Test
    void dispatchSerializesEmoteIntent() {
        var bridge = new VilleWorldBridge();
        var queue = new IntentQueue<VilleIntent>();
        queue.enqueue(new VilleIntent.Emote("wave"));

        var sent = new ArrayList<String>();
        bridge.setSendFunction(sent::add);
        bridge.dispatch(queue);

        assertThat(sent.get(0)).contains("EMOTE");
        assertThat(sent.get(0)).contains("wave");
    }
}
