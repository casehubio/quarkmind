package io.quarkmind.plugin.commentary;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes commentary LLM calls inline, bypassing the engine's async worker dispatch.
 *
 * <p>Supports both reactive and narrative commentary types. In replay sync mode,
 * {@link #executeWithTimeout} blocks until the LLM responds (or timeout), allowing
 * the game tick to pause at commentary-worthy moments.
 *
 * <p>Refs #181, #290
 */
@ApplicationScoped
public class InlineCommentaryDispatcher {

    private static final Logger log = Logger.getLogger(InlineCommentaryDispatcher.class);

    private final ChatModel                  chatModel;
    private final AgentDescriptor            reactiveDescriptor;
    private final AgentDescriptor            narrativeDescriptor;
    private final Event<CommentaryCompleted> completedEvent;
    private final boolean                    available;

    @Inject
    InlineCommentaryDispatcher(Instance<ChatModel> chatModelInstance,
                               Instance<QuarkMindAgentRegistrar> registrarInstance,
                               Event<CommentaryCompleted> completedEvent) {
        this.completedEvent = completedEvent;
        if (!chatModelInstance.isResolvable() || !registrarInstance.isResolvable()) {
            this.chatModel           = null;
            this.reactiveDescriptor  = null;
            this.narrativeDescriptor = null;
            this.available           = false;
            return;
        }
        this.chatModel = chatModelInstance.get();
        var descriptors = registrarInstance.get().descriptors();
        this.reactiveDescriptor  = findDescriptor(descriptors, "commentary-reactive");
        this.narrativeDescriptor = findDescriptor(descriptors, "commentary-narrative");
        this.available           = reactiveDescriptor != null;
        if (available) {
            log.infof("[INLINE-COMMENTARY] Ready — reactive: %s, narrative: %s",
                      reactiveDescriptor.agentId(),
                      narrativeDescriptor != null ? narrativeDescriptor.agentId() : "none");
        }
    }

    InlineCommentaryDispatcher(ChatModel chatModel,
                               AgentDescriptor reactiveDescriptor,
                               AgentDescriptor narrativeDescriptor,
                               Event<CommentaryCompleted> completedEvent) {
        this.chatModel           = chatModel;
        this.reactiveDescriptor  = reactiveDescriptor;
        this.narrativeDescriptor = narrativeDescriptor;
        this.completedEvent      = completedEvent;
        this.available           = reactiveDescriptor != null;
    }

    public boolean isAvailable() {return available;}

    /**
     * Fire-and-forget async dispatch — backward-compatible single-arg form.
     */
    public void executeAsync(Map<String, Object> triggers) {
        executeAsync(triggers, CommentaryType.REACTIVE);
    }

    /**
     * Fire-and-forget async dispatch (live game modes).
     */
    public void executeAsync(Map<String, Object> triggers, CommentaryType type) {
        if (!available) {return;}
        Thread.startVirtualThread(() -> {
            try {
                execute(triggers, type);
            } catch (Exception e) {
                log.warnf(e, "[INLINE-COMMENTARY] %s failed: %s", type, e.getMessage());
            }
        });
    }

    /**
     * Synchronous dispatch with timeout (replay sync mode).
     * Runs the LLM call on a virtual thread and blocks the caller until
     * completion or timeout. On timeout, logs a warning and returns — the
     * commentary is skipped for this moment.
     */
    public void executeWithTimeout(Map<String, Object> triggers,
                                   CommentaryType type, int timeoutSeconds) {
        if (!available) {return;}
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> execute(triggers, type), Thread::startVirtualThread);
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warnf("[INLINE-COMMENTARY] %s timed out after %ds — skipping",
                      type, timeoutSeconds);
        } catch (Exception e) {
            log.warnf(e, "[INLINE-COMMENTARY] %s sync failed: %s", type, e.getMessage());
        }
    }

    void execute(Map<String, Object> triggers, CommentaryType type) {
        long startNanos = System.nanoTime();
        AgentDescriptor descriptor = type == CommentaryType.REACTIVE
                                     ? reactiveDescriptor : narrativeDescriptor;
        if (descriptor == null) {return;}

        String systemPrompt = type == CommentaryType.REACTIVE
                              ? CommentaryWorkerFactory.buildReactiveSystemPrompt(descriptor)
                              : CommentaryWorkerFactory.buildNarrativeSystemPrompt(descriptor);
        String userMessage = type == CommentaryType.REACTIVE
                             ? CommentaryWorkerFactory.buildReactiveUserMessage(triggers)
                             : CommentaryWorkerFactory.buildNarrativeUserMessage(triggers);

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                                                          .messages(new SystemMessage(systemPrompt), new UserMessage(userMessage))
                                                          .build());

        String text      = response.aiMessage().text();
        long   latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        long   gameFrame = extractGameFrame(triggers, type);

        String capability = type == CommentaryType.REACTIVE
                            ? "commentary-reactive" : "commentary-narrative";
        completedEvent.fire(new CommentaryCompleted(
                descriptor.agentId(), capability,
                gameFrame, text != null ? text : "", type, latencyMs));

        log.infof("[INLINE-COMMENTARY] %s completed in %dms at frame %d: %s",
                  type, latencyMs, gameFrame,
                  text != null ? text.substring(0, Math.min(80, text.length())) : "");
    }

    private static long extractGameFrame(Map<String, Object> triggers, CommentaryType type) {
        String key = type == CommentaryType.REACTIVE
                     ? QuarkMindCaseFile.COMMENTARY_TRIGGER
                     : QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER;
        Object trigger = triggers.get(key);
        if (trigger instanceof Map<?, ?> m) {
            Object frame = m.get("gameFrame");
            if (frame instanceof Number n) {return n.longValue();}
        }
        return 0L;
    }

    private static AgentDescriptor findDescriptor(
            java.util.List<AgentDescriptor> descriptors, String capabilityName) {
        return descriptors.stream()
                          .filter(d -> d.capabilities().stream()
                                        .anyMatch(c -> c.name().equals(capabilityName)))
                          .findFirst().orElse(null);
    }
}
