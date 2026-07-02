package io.quarkmind.agent;

import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AdvisoryInvocationCounter} — verifies invocation tracking
 * and lifecycle reset on {@link GameStarted}.
 *
 * <p>Refs #180
 */
class AdvisoryInvocationCounterTest {

    @Test
    void record_addsToSet() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis-aggressive@v1");
        counter.record("claude:strategic-cautious@v1");

        Set<String> snapshot = counter.snapshot();
        assertThat(snapshot).containsExactlyInAnyOrder(
            "claude:crisis-aggressive@v1",
            "claude:strategic-cautious@v1"
        );
    }

    @Test
    void record_duplicatesIgnored() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis-aggressive@v1");
        counter.record("claude:crisis-aggressive@v1");

        Set<String> snapshot = counter.snapshot();
        assertThat(snapshot).containsExactly("claude:crisis-aggressive@v1");
    }

    @Test
    void snapshot_isUnmodifiable() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis-aggressive@v1");

        Set<String> snapshot = counter.snapshot();
        assertThat(snapshot).isUnmodifiable();
    }

    @Test
    void onGameStarted_clearsSet() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis-aggressive@v1");
        counter.record("claude:strategic-cautious@v1");

        counter.onGameStarted(new GameStarted());

        Set<String> snapshot = counter.snapshot();
        assertThat(snapshot).isEmpty();
    }

    @Test
    void emptySnapshot_isEmpty() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        Set<String> snapshot = counter.snapshot();
        assertThat(snapshot).isEmpty();
    }
}
