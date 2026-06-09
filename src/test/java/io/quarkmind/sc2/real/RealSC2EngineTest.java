package io.quarkmind.sc2.real;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RealSC2Engine lifecycle contracts.
 * Instantiates directly — no CDI required.
 */
class RealSC2EngineTest {

    @Test
    void connectFallback_throwsIllegalStateException_haltingLifecycleChain() {
        // connectFallback() must throw — returning normally would let subsequent
        // joinGame() calls hit a null socket on an unconnected transport.
        var engine = new RealSC2Engine();
        assertThatThrownBy(engine::connectFallback)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("game cannot start");
    }
}
