package io.quarkmind.plugin.commentary;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agent.QuarkMindCaseFile;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InlineCommentaryDispatcherTest {

    private ChatModel chatModel;
    private Event<CommentaryCompleted> completedEvent;
    private AgentDescriptor reactiveDescriptor;
    private AgentDescriptor narrativeDescriptor;
    private InlineCommentaryDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = mock(ChatModel.class);
        completedEvent = mock(Event.class);
        reactiveDescriptor = stubDescriptor("commentator-energetic", "commentary-reactive");
        narrativeDescriptor = stubDescriptor("narrator-dramatic", "commentary-narrative");

        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(new AiMessage("test commentary"));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        dispatcher = new InlineCommentaryDispatcher(
            chatModel, reactiveDescriptor, narrativeDescriptor, completedEvent);
    }

    @Test
    void execute_narrativeType_usesNarrativePrompts() {
        Map<String, Object> triggers = Map.of(
            QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER,
            Map.of("gameFrame", 1000L, "moments", "test"));

        dispatcher.execute(triggers, CommentaryType.NARRATIVE);

        ArgumentCaptor<CommentaryCompleted> captor = ArgumentCaptor.forClass(CommentaryCompleted.class);
        verify(completedEvent).fire(captor.capture());
        assertThat(captor.getValue().commentaryType()).isEqualTo(CommentaryType.NARRATIVE);
        assertThat(captor.getValue().capability()).isEqualTo("commentary-narrative");
        assertThat(captor.getValue().workerId()).isEqualTo("narrator-dramatic");
    }

    @Test
    void execute_reactiveType_usesReactivePrompts() {
        Map<String, Object> triggers = Map.of(
            QuarkMindCaseFile.COMMENTARY_TRIGGER,
            Map.of("gameFrame", 500L, "momentTypes", "FIRST_CONTACT"));

        dispatcher.execute(triggers, CommentaryType.REACTIVE);

        ArgumentCaptor<CommentaryCompleted> captor = ArgumentCaptor.forClass(CommentaryCompleted.class);
        verify(completedEvent).fire(captor.capture());
        assertThat(captor.getValue().commentaryType()).isEqualTo(CommentaryType.REACTIVE);
        assertThat(captor.getValue().capability()).isEqualTo("commentary-reactive");
    }

    @Test
    void executeWithTimeout_completesWithinTimeout() {
        Map<String, Object> triggers = Map.of(
            QuarkMindCaseFile.COMMENTARY_TRIGGER,
            Map.of("gameFrame", 200L, "momentTypes", "BATTLE_ENDED"));

        dispatcher.executeWithTimeout(triggers, CommentaryType.REACTIVE, 5);

        verify(completedEvent).fire(any(CommentaryCompleted.class));
    }

    @Test
    void executeWithTimeout_timeoutSkips() {
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            Thread.sleep(5000);
            return null;
        });

        Map<String, Object> triggers = Map.of(
            QuarkMindCaseFile.COMMENTARY_TRIGGER,
            Map.of("gameFrame", 300L, "momentTypes", "FIRST_CONTACT"));

        dispatcher.executeWithTimeout(triggers, CommentaryType.REACTIVE, 1);

        verify(completedEvent, never()).fire(any());
    }

    @Test
    void executeAsync_backwardCompat_singleArg() throws InterruptedException {
        Map<String, Object> triggers = Map.of(
            QuarkMindCaseFile.COMMENTARY_TRIGGER,
            Map.of("gameFrame", 100L, "momentTypes", "ARMY_SHIFT"));

        dispatcher.executeAsync(triggers);

        Thread.sleep(500);
        verify(completedEvent).fire(any(CommentaryCompleted.class));
    }

    @Test
    void isAvailable_trueWhenReactiveDescriptorPresent() {
        assertThat(dispatcher.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_falseWhenNoReactiveDescriptor() {
        var unavailable = new InlineCommentaryDispatcher(
            chatModel, null, narrativeDescriptor, completedEvent);
        assertThat(unavailable.isAvailable()).isFalse();
    }

    private static AgentDescriptor stubDescriptor(String agentId, String capabilityName) {
        var cap = mock(AgentCapability.class);
        when(cap.name()).thenReturn(capabilityName);
        var desc = mock(AgentDescriptor.class);
        when(desc.agentId()).thenReturn(agentId);
        when(desc.capabilities()).thenReturn(List.of(cap));
        when(desc.disposition()).thenReturn(null);
        return desc;
    }
}
