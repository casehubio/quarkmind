package io.quarkmind.agent;

import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryInvocationCounterTest {

    @Test
    void record_addsToSnapshot() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis@v1", 1000L);
        counter.record("claude:strategic@v1", 2000L);

        assertThat(counter.snapshot())
                .containsExactlyInAnyOrder("claude:crisis@v1", "claude:strategic@v1");
    }

    @Test
    void record_storesFirstFrameOnly() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis@v1", 1000L);
        counter.record("claude:crisis@v1", 5000L);

        assertThat(counter.firstFrame("claude:crisis@v1"))
                .isEqualTo(OptionalLong.of(1000L));
    }

    @Test
    void firstFrame_returnsEmpty_whenNotRecorded() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        assertThat(counter.firstFrame("claude:unknown@v1"))
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void snapshot_isUnmodifiable() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis@v1", 1000L);

        assertThat(counter.snapshot()).isUnmodifiable();
    }

    @Test
    void onGameStarted_clearsAll() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        counter.record("claude:crisis@v1", 1000L);
        counter.record("claude:strategic@v1", 2000L);

        counter.onGameStarted(new GameStarted());

        assertThat(counter.snapshot()).isEmpty();
        assertThat(counter.firstFrame("claude:crisis@v1"))
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void emptySnapshot_isEmpty() {
        AdvisoryInvocationCounter counter = new AdvisoryInvocationCounter();
        assertThat(counter.snapshot()).isEmpty();
    }
}
