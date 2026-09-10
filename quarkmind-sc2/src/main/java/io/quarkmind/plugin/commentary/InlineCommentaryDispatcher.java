package io.quarkmind.plugin.commentary;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Executes commentary LLM calls inline, bypassing the engine's async worker dispatch.
 *
 * <p>The engine's settlement tracker does not wire worker completion signals, so workers
 * dispatched via {@code caseHub.signal()} never execute. This dispatcher calls the
 * ChatModel directly on a virtual thread when commentary triggers fire.
 */
@ApplicationScoped
public class InlineCommentaryDispatcher {

    private static final Logger log = Logger.getLogger(InlineCommentaryDispatcher.class);

    private final ChatModel chatModel;
    private final AgentDescriptor reactiveDescriptor;
    private final Event<CommentaryCompleted> completedEvent;
    private final boolean available;

    @Inject
    InlineCommentaryDispatcher(Instance<ChatModel> chatModelInstance,
                               Instance<QuarkMindAgentRegistrar> registrarInstance,
                               Event<CommentaryCompleted> completedEvent) {
        this.completedEvent = completedEvent;
        if (!chatModelInstance.isResolvable() || !registrarInstance.isResolvable()) {
            this.chatModel = null;
            this.reactiveDescriptor = null;
            this.available = false;
            return;
        }
        this.chatModel = chatModelInstance.get();
        var descriptors = registrarInstance.get().descriptors();
        this.reactiveDescriptor = descriptors.stream()
            .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("commentary-reactive")))
            .findFirst().orElse(null);
        this.available = reactiveDescriptor != null;
        if (available) {
            log.infof("[INLINE-COMMENTARY] Ready — descriptor: %s", reactiveDescriptor.agentId());
        }
    }

    public boolean isAvailable() { return available; }

    /**
     * Executes reactive commentary asynchronously on a virtual thread.
     *
     * @param triggers the commentary trigger map (containing {@code game.commentary.trigger})
     */
    public void executeAsync(Map<String, Object> triggers) {
        if (!available) return;
        Thread.startVirtualThread(() -> {
            try {
                execute(triggers);
            } catch (Exception e) {
                log.warnf(e, "[INLINE-COMMENTARY] Failed: %s", e.getMessage());
            }
        });
    }

    void execute(Map<String, Object> triggers) {
        long startNanos = System.nanoTime();
        String systemPrompt = CommentaryWorkerFactory.buildReactiveSystemPrompt(reactiveDescriptor);
        String userMessage = CommentaryWorkerFactory.buildReactiveUserMessage(triggers);

        ChatResponse response = chatModel.chat(ChatRequest.builder()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMessage))
            .build());

        String text = response.aiMessage().text();
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        long gameFrame = extractGameFrame(triggers);

        completedEvent.fire(new CommentaryCompleted(
            reactiveDescriptor.agentId(), "commentary-reactive",
            gameFrame, text != null ? text : "", CommentaryType.REACTIVE, latencyMs));

        log.infof("[INLINE-COMMENTARY] Completed in %dms at frame %d: %s",
            latencyMs, gameFrame, text != null ? text.substring(0, Math.min(80, text.length())) : "");
    }

    private static long extractGameFrame(Map<String, Object> triggers) {
        Object trigger = triggers.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        if (trigger instanceof Map<?, ?> m) {
            Object frame = m.get("gameFrame");
            if (frame instanceof Number n) return n.longValue();
        }
        return 0L;
    }
}
