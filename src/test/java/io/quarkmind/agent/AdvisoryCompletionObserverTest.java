package io.quarkmind.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AdvisoryCompletionObserver} — verifies it records invocations
 * on advisory completion.
 *
 * <p>Plain JUnit (no @QuarkusTest) — manually constructs observer with real counter.
 *
 * <p>Refs #180
 */
class AdvisoryCompletionObserverTest {

    @Test
    void onAdvisoryCompleted_recordsInvocation() {
        // Given: real counter
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        AdvisoryCompletionObserver observer = new AdvisoryCompletionObserver(counter);

        // When: advisory completed
        AdvisoryCompleted completed = new AdvisoryCompleted(
            "claude:crisis-aggressive@v1",
            "advisory-crisis",
            1000L,
            "Recommendation text",
            0.85,
            120L,
            Map.of()
        );
        observer.onAdvisoryCompleted(completed);

        // Then: invocation recorded
        assertThat(counter.snapshot()).containsExactly("claude:crisis-aggressive@v1");
    }

    @Test
    void onAdvisoryCompleted_multipleAdvisors() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        AdvisoryCompletionObserver observer = new AdvisoryCompletionObserver(counter);

        // When: two different advisors complete
        AdvisoryCompleted crisis = new AdvisoryCompleted(
            "claude:crisis-aggressive@v1", "advisory-crisis", 1000L, "Crisis rec", 0.9, 100L, Map.of()
        );
        AdvisoryCompleted strategic = new AdvisoryCompleted(
            "claude:strategic-cautious@v1", "advisory-strategic", 2000L, "Strategic rec", 0.7, 150L, Map.of()
        );

        observer.onAdvisoryCompleted(crisis);
        observer.onAdvisoryCompleted(strategic);

        // Then: both recorded
        assertThat(counter.snapshot()).containsExactlyInAnyOrder(
            "claude:crisis-aggressive@v1",
            "claude:strategic-cautious@v1"
        );
    }

    @Test
    void onAdvisoryCompleted_recordsFirstInvocationFrame() {
        AdvisoryInvocationCounter  counter  = new AdvisoryInvocationCounter();
        AdvisoryCompletionObserver observer = new AdvisoryCompletionObserver(counter);

        AdvisoryCompleted first = new AdvisoryCompleted(
                "claude:crisis-aggressive@v1", "advisory-crisis", 1000L,
                "First rec", 0.85, 120L, Map.of()
        );
        AdvisoryCompleted second = new AdvisoryCompleted(
                "claude:crisis-aggressive@v1", "advisory-crisis", 5000L,
                "Second rec", 0.90, 100L, Map.of()
        );

        observer.onAdvisoryCompleted(first);
        observer.onAdvisoryCompleted(second);

        assertThat(counter.firstFrame("claude:crisis-aggressive@v1"))
                .isEqualTo(java.util.OptionalLong.of(1000L));
    }
}
