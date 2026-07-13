package io.quarkmind.plugin.advisory;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryChannelBackendTest {

    private AdvisoryChannelBackend backend;

    @BeforeEach
    void setUp() {
        backend = new AdvisoryChannelBackend();
    }

    @Test
    void backendId_isQuarkmindAdvisoryObserver() {
        assertThat(backend.backendId()).isEqualTo("quarkmind-advisory-observer");
    }

    @Test
    void actorType_isHuman() {
        assertThat(backend.actorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void post_storesLatestMessage() {
        var channelRef = new ChannelRef(UUID.randomUUID(), "quarkmind-advisory");
        var message = new OutboundMessage(
            UUID.randomUUID(),
            "summarisation.advisory-broker",
            MessageType.STATUS,
            "{\"advisorId\":\"claude:crisis-aggressive@v1\",\"recommendation\":\"Build Shield Batteries\"}",
            null, null, ActorType.AGENT, List.of()
        );

        backend.post(channelRef, message);

        assertThat(backend.latestMessage()).isNotNull();
        assertThat(backend.latestMessage()).isSameAs(message);
        assertThat(backend.latestMessage().content()).contains("crisis-aggressive");
    }

    @Test
    void post_replacesLatestMessage() {
        var channelRef = new ChannelRef(UUID.randomUUID(), "quarkmind-advisory");
        var first = new OutboundMessage(
            UUID.randomUUID(), "broker", MessageType.STATUS, "first",
            null, null, ActorType.AGENT, List.of());
        var second = new OutboundMessage(
            UUID.randomUUID(), "broker", MessageType.STATUS, "second",
            null, null, ActorType.AGENT, List.of());

        backend.post(channelRef, first);
        backend.post(channelRef, second);

        assertThat(backend.latestMessage()).isSameAs(second);
    }

    @Test
    void latestMessage_isNullBeforeAnyPost() {
        assertThat(backend.latestMessage()).isNull();
    }

    @Test
    void open_doesNotThrow() {
        var channelRef = new ChannelRef(UUID.randomUUID(), "quarkmind-advisory");
        backend.open(channelRef, Map.of());
    }

    @Test
    void close_doesNotThrow() {
        var channelRef = new ChannelRef(UUID.randomUUID(), "quarkmind-advisory");
        backend.close(channelRef);
    }

    @Test
    void messageCount_tracksPostCalls() {
        var channelRef = new ChannelRef(UUID.randomUUID(), "quarkmind-advisory");
        assertThat(backend.messageCount()).isZero();

        backend.post(channelRef, new OutboundMessage(
            UUID.randomUUID(), "broker", MessageType.STATUS, "msg1",
            null, null, ActorType.AGENT, List.of()));
        assertThat(backend.messageCount()).isEqualTo(1);

        backend.post(channelRef, new OutboundMessage(
            UUID.randomUUID(), "broker", MessageType.STATUS, "msg2",
            null, null, ActorType.AGENT, List.of()));
        assertThat(backend.messageCount()).isEqualTo(2);
    }
}
