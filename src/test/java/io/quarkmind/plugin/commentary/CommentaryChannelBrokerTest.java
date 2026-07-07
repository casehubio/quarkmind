package io.quarkmind.plugin.commentary;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommentaryChannelBroker} — Qhorus commentary audit trail.
 *
 * <p>Plain JUnit test — manually constructs broker with mock dependencies.
 * Follows AdvisoryChannelBrokerTest pattern (no @QuarkusTest, no database).
 *
 * <p>Refs #181
 */
class CommentaryChannelBrokerTest {

    @Test
    void onCommentaryCompleted_dispatchesStatusMessage() {
        // Given: mock dependencies
        MessageService messageService = mock(MessageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID channelId = UUID.randomUUID();

        // Broker with channelId already set (bypass @PostConstruct)
        CommentaryChannelBroker broker = new CommentaryChannelBroker();
        broker.messageService = messageService;
        broker.objectMapper = objectMapper;
        setChannelId(broker, channelId);

        // Mock dispatch result
        when(messageService.dispatch(any(MessageDispatch.class)))
            .thenReturn(new DispatchResult(123L, channelId, "commentary.reactive",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, null));

        // Commentary event (reactive)
        var event = new CommentaryCompleted(
            "claude:commentator-energetic@v1",
            "commentary-reactive",
            1200L,
            "The enemy is at the gates — pulling back to defend!",
            CommentaryType.REACTIVE,
            450L
        );

        // When: broker observes the event
        broker.onCommentaryCompleted(event);

        // Then: a STATUS message was dispatched with correct parameters
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());

        MessageDispatch dispatch = captor.getValue();
        assertThat(dispatch.channelId()).isEqualTo(channelId);
        assertThat(dispatch.sender()).isEqualTo("commentary.reactive");
        assertThat(dispatch.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatch.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(dispatch.content()).contains("claude:commentator-energetic@v1");
        assertThat(dispatch.content()).contains("commentary-reactive");
        assertThat(dispatch.content()).contains("1200");
        assertThat(dispatch.content()).contains("enemy is at the gates");
    }

    @Test
    void onCommentaryCompleted_jsonSerializesFullEvent() throws Exception {
        // Given: real ObjectMapper for JSON validation
        MessageService messageService = mock(MessageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID channelId = UUID.randomUUID();

        CommentaryChannelBroker broker = new CommentaryChannelBroker();
        broker.messageService = messageService;
        broker.objectMapper = objectMapper;
        setChannelId(broker, channelId);

        when(messageService.dispatch(any(MessageDispatch.class)))
            .thenReturn(new DispatchResult(456L, channelId, "commentary.narrative",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, null));

        var event = new CommentaryCompleted(
            "claude:narrator-tactical@v1",
            "commentary-narrative",
            2400L,
            "Over the last minute, the bot secured map control with three skirmishes while expanding to a third base.",
            CommentaryType.NARRATIVE,
            1800L
        );

        // When: broker handles the event
        broker.onCommentaryCompleted(event);

        // Then: JSON payload contains all fields
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());

        String json = captor.getValue().content();
        assertThat(json).contains("claude:narrator-tactical@v1");
        assertThat(json).contains("commentary-narrative");
        assertThat(json).contains("2400");
        assertThat(json).contains("Over the last minute");
        assertThat(json).contains("NARRATIVE");
        assertThat(json).contains("1800");

        // Validate JSON is parseable
        CommentaryCompleted deserialized = objectMapper.readValue(json, CommentaryCompleted.class);
        assertThat(deserialized.workerId()).isEqualTo("claude:narrator-tactical@v1");
        assertThat(deserialized.commentaryType()).isEqualTo(CommentaryType.NARRATIVE);
        assertThat(deserialized.text()).contains("secured map control");
    }

    @Test
    void onCommentaryCompleted_usesTypeNameForSender() {
        // Given: verify sender naming matches commentaryType
        MessageService messageService = mock(MessageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID channelId = UUID.randomUUID();

        CommentaryChannelBroker broker = new CommentaryChannelBroker();
        broker.messageService = messageService;
        broker.objectMapper = objectMapper;
        setChannelId(broker, channelId);

        when(messageService.dispatch(any(MessageDispatch.class)))
            .thenReturn(new DispatchResult(789L, channelId, "commentary.narrative",
                MessageType.STATUS, null, null, null, null, null, null, null, 0, null));

        var narrativeEvent = new CommentaryCompleted(
            "claude:narrator-dramatic@v1",
            "commentary-narrative",
            3000L,
            "The tide has turned!",
            CommentaryType.NARRATIVE,
            2000L
        );

        // When: narrative event fired
        broker.onCommentaryCompleted(narrativeEvent);

        // Then: sender is "commentary.narrative"
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo("commentary.narrative");
    }

    /** Reflection helper to set private channelId field (bypasses @PostConstruct). */
    private void setChannelId(CommentaryChannelBroker broker, UUID id) {
        try {
            var field = CommentaryChannelBroker.class.getDeclaredField("channelId");
            field.setAccessible(true);
            field.set(broker, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
