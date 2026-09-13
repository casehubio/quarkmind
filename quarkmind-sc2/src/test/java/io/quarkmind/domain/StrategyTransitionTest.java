package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrategyTransitionTest {

    @Test
    void constructionWithPath() {
        var path = new TransitionPath("Marine Rush → Bio Timing", "Get Stalkers and Colossus tech");
        var t = new StrategyTransition(
            StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.TERRAN_BIO_TIMING,
            0.35, 0.62, 5000L, path);
        assertEquals(StrategyArchetype.TERRAN_MARINE_RUSH, t.from());
        assertEquals(StrategyArchetype.TERRAN_BIO_TIMING, t.to());
        assertEquals(0.35, t.fromConfidence(), 0.001);
        assertEquals(0.62, t.toConfidence(), 0.001);
        assertEquals(5000L, t.detectedAtFrame());
        assertNotNull(t.path());
        assertEquals("Marine Rush → Bio Timing", t.path().displayName());
    }

    @Test
    void constructionWithNullPath() {
        var t = new StrategyTransition(
            StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.TERRAN_BIO_TIMING,
            0.35, 0.62, 5000L, null);
        assertNull(t.path());
    }

    @Test
    void equalityAndHashCode() {
        var path = new TransitionPath("display", "advice");
        var a = new StrategyTransition(StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.TERRAN_BIO_TIMING, 0.3, 0.6, 100L, path);
        var b = new StrategyTransition(StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.TERRAN_BIO_TIMING, 0.3, 0.6, 100L, path);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
