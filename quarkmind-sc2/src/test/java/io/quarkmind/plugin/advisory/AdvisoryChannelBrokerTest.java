package io.quarkmind.plugin.advisory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.agent.AdvisoryCompleted;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdvisoryChannelBroker} — Qhorus advisory audit trail.
 *
 * <p>Plain JUnit test — manually constructs broker with mock dependencies.
 * Follows ScoutingIntelBrokerTest pattern (no @QuarkusTest, no database).
 *
 * <p>Refs #180
 */
class AdvisoryChannelBrokerTest {

    @Test
    void onAdvisoryCompleted_dispatchesStatusMessage() {
        // Given: mock dependencies
        MessageService messageService = mock(MessageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID channelId = UUID.randomUUID();

        // Broker with channelId already set (bypass @PostConstruct)
        AdvisoryChannelBroker broker = new AdvisoryChannelBroker();
        broker.messageService = messageService;
        broker.objectMapper = objectMapper;
        setChannelId(broker, channelId);

        // Mock dispatch result
        when(messageService.dispatch(any(MessageDispatch.class)))
            .thenReturn(new DispatchResult(123L, channelId, "summarisation.advisory-broker",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, null));

        // Advisory event
        var event = new AdvisoryCompleted(
            "claude:crisis-aggressive@v1",
            "advisory-crisis",
            1200L,
            "Build 2 Shield Batteries immediately",
            0.87,
            450L,
            Map.of("minerals", 450.0, "supply", 32.0, "army", 8.0)
        );

        // When: broker observes the event
        broker.onAdvisoryCompleted(event);

        // Then: a STATUS message was dispatched with correct parameters
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());

        MessageDispatch dispatch = captor.getValue();
        assertThat(dispatch.channelId()).isEqualTo(channelId);
        assertThat(dispatch.sender()).isEqualTo("summarisation.advisory-broker");
        assertThat(dispatch.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatch.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(dispatch.content()).contains("claude:crisis-aggressive@v1");
        assertThat(dispatch.content()).contains("advisory-crisis");
        assertThat(dispatch.content()).contains("1200");
        assertThat(dispatch.content()).contains("Build 2 Shield Batteries immediately");
    }

    @Test
    void onAdvisoryCompleted_jsonSerializesFullEvent() throws Exception {
        // Given: real ObjectMapper for JSON validation
        MessageService messageService = mock(MessageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID channelId = UUID.randomUUID();

        AdvisoryChannelBroker broker = new AdvisoryChannelBroker();
        broker.messageService = messageService;
        broker.objectMapper = objectMapper;
        setChannelId(broker, channelId);

        when(messageService.dispatch(any(MessageDispatch.class)))
            .thenReturn(new DispatchResult(456L, channelId, "summarisation.advisory-broker",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, null));

        var event = new AdvisoryCompleted(
            "claude:economic-careful@v1",
            "advisory-economic",
            800L,
            "Expand to third base",
            0.92,
            380L,
            Map.of("minerals", 600.0, "vespene", 200.0, "workers", 45.0)
        );

        // When: broker handles the event
        broker.onAdvisoryCompleted(event);

        // Then: JSON payload contains all fields
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());

        String json = captor.getValue().content();
        assertThat(json).contains("claude:economic-careful@v1");
        assertThat(json).contains("advisory-economic");
        assertThat(json).contains("800");
        assertThat(json).contains("Expand to third base");
        assertThat(json).contains("0.92");
        assertThat(json).contains("380");
        assertThat(json).contains("\"minerals\":600.0");
        assertThat(json).contains("\"workers\":45.0");

        // Validate JSON is parseable
        AdvisoryCompleted deserialized = objectMapper.readValue(json, AdvisoryCompleted.class);
        assertThat(deserialized.advisorId()).isEqualTo("claude:economic-careful@v1");
        assertThat(deserialized.confidence()).isEqualTo(0.92);
        assertThat(deserialized.gameStateSnapshot()).containsEntry("minerals", 600.0);
    }

    /** Reflection helper to set private channelId field (bypasses @PostConstruct). */
    private void setChannelId(AdvisoryChannelBroker broker, UUID id) {
        try {
            var field = AdvisoryChannelBroker.class.getDeclaredField("channelId");
            field.setAccessible(true);
            field.set(broker, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
