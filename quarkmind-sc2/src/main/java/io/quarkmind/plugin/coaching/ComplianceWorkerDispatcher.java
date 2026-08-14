package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ComplianceWorkerDispatcher {

    private static final Logger log = Logger.getLogger(ComplianceWorkerDispatcher.class);

    @Inject
    Instance<dev.langchain4j.model.chat.ChatModel> chatModel;
    @Inject
    CoachingEffectivenessTrustRecorder recorder;
    @Inject
    jakarta.enterprise.event.Event<CoachingComplianceResolved> complianceResolvedEvent;
    @Inject
    ManagedExecutor executor;
    @ConfigProperty(name = "quarkmind.coaching.compliance.llm-timeout-seconds", defaultValue = "10")
    int timeoutSeconds;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    ComplianceWorkerDispatcher() {}

    public boolean isAvailable() {
        return chatModel != null && chatModel.isResolvable();
    }

    public void dispatch(OpenCommitment commitment, GameState currentState) {
        String correlationId = commitment.correlationId();
        trackInFlight(correlationId);

        String summary = LlmComplianceWorkerFactory.summariseForCompliance(
                commitment.baselineState(), currentState, commitment.advice().advice());

        CompletableFuture.supplyAsync(() -> {
                             var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                                                                                         .messages(
                                                                                                 new dev.langchain4j.data.message.SystemMessage(LlmComplianceWorkerFactory.buildSystemPrompt()),
                                                                                                 new dev.langchain4j.data.message.UserMessage(summary))
                                                                                         .build();
                             return chatModel.get().chat(request);
                         }, executor)
                         .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                         .whenComplete((response, throwable) -> {
                             if (!removeFromFlight(correlationId)) {
                                 log.debugf("[LLM-COMPLIANCE] Discarding result for %s — no longer in flight", correlationId);
                                 return;
                             }
                             if (throwable != null) {
                                 log.warnf("[LLM-COMPLIANCE] Evaluation failed for %s: %s", correlationId, throwable.getMessage());
                                 recorder.record(correlationId, commitment.agentId(), "NEUTRAL", commitment.advice());
                                 fireResolved(commitment, "NEUTRAL");
                                 return;
                             }
                             ComplianceVerdict verdict = ComplianceVerdict.parse(response.aiMessage().text());
                             String            outcome = mapVerdictToOutcome(verdict.verdict());
                             log.infof("[LLM-COMPLIANCE] %s → %s (confidence=%.2f, reason=%s)",
                                       correlationId, outcome, verdict.confidence(), verdict.reasoning());
                             recorder.record(correlationId, commitment.agentId(), outcome, commitment.advice());
                             fireResolved(commitment, outcome);
                         });
    }

    public void cancelAll() {
        inFlight.clear();
    }

    void trackInFlight(String correlationId) {
        inFlight.add(correlationId);
    }

    boolean removeFromFlight(String correlationId) {
        return inFlight.remove(correlationId);
    }

    boolean isInFlight(String correlationId) {
        return inFlight.contains(correlationId);
    }

    static String mapVerdictToOutcome(String verdict) {
        return switch (verdict) {
            case "COMPLIED" -> "ENDORSED";
            case "PARTIALLY" -> "PARTIAL";
            case "IGNORED" -> "CHALLENGED";
            default -> "NEUTRAL";
        };
    }

    private void fireResolved(OpenCommitment commitment, String status) {
        if (complianceResolvedEvent != null) {
            complianceResolvedEvent.fireAsync(new CoachingComplianceResolved(
                commitment.issuedAtFrame(), commitment.advice().domainTag(), status, commitment.correlationId()));
        }
    }

    @FunctionalInterface
    public interface Callback {
        void onCompleted(String correlationId, String agentId, ComplianceVerdict verdict,
                         CoachingAdvice advice, long gameFrame);
    }
}
