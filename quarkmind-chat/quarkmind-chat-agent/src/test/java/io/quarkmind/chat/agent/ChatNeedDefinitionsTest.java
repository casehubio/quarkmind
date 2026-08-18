package io.quarkmind.chat.agent;

import io.quarkmind.agency.needs.NeedState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatNeedDefinitionsTest {

    @Test
    void socialDecaysOnIdle() {
        var needs = new NeedState();
        needs.set("SOCIAL", 80.0);
        var defs = new ChatNeedDefinitions(0.5, 0.1, 0.2);
        defs.decayAll(needs);
        assertEquals(79.5, needs.get("SOCIAL"), 0.01);
    }

    @Test
    void curiosityDecaysOnIdle() {
        var needs = new NeedState();
        needs.set("CURIOSITY", 50.0);
        var defs = new ChatNeedDefinitions(0.5, 0.1, 0.2);
        defs.decayAll(needs);
        assertEquals(49.9, needs.get("CURIOSITY"), 0.01);
    }

    @Test
    void socialSatisfiedByConversation() {
        var needs = new NeedState();
        needs.set("SOCIAL", 30.0);
        needs.satisfy("SOCIAL", 20.0);
        assertEquals(50.0, needs.get("SOCIAL"), 0.01);
    }

    @Test
    void expressionBuildsOverTime() {
        var needs = new NeedState();
        needs.set("EXPRESSION", 0.0);
        var defs = new ChatNeedDefinitions(0.5, 0.1, 0.2);
        defs.buildExpression(needs);
        assertEquals(0.2, needs.get("EXPRESSION"), 0.01);
    }

    @Test
    void expressionClampsAtMax() {
        var needs = new NeedState();
        needs.set("EXPRESSION", 99.9);
        var defs = new ChatNeedDefinitions(0.5, 0.1, 0.2);
        defs.buildExpression(needs);
        assertEquals(100.0, needs.get("EXPRESSION"), 0.01);
    }
}
