package io.quarkmind.plugin.commentary;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkmind.agent.QuarkMindCaseFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommentaryWorkerFactory}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Reactive workers filtered by "commentary-reactive" capability</li>
 *   <li>Narrative workers filtered by "commentary-narrative" capability</li>
 *   <li>System prompts include disposition traits</li>
 *   <li>Response is plain text (no RECOMMENDATION/REASONING/CONFIDENCE parsing)</li>
 *   <li>Error handling returns failed WorkerResult</li>
 * </ul>
 *
 * <p>Refs #181 (Task 5)
 */
class CommentaryWorkerFactoryTest {

    @Test
    void createReactiveWorkers_filtersReactiveCapability() {
        List<AgentDescriptor> descriptors = List.of(
            buildReactiveDescriptor("claude:commentator-energetic@v1", "Energetic Commentator", "bold"),
            buildNarrativeDescriptor("claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible"),
            buildReactiveDescriptor("claude:commentator-analytical@v1", "Analytical Commentator", "conservative")
        );
        ChatModel stubModel = stubChatModel("The enemy is at the gates!");

        List<Worker> workers = CommentaryWorkerFactory.createReactiveWorkers(
            descriptors, stubModel, noOpCallback());

        assertThat(workers).hasSize(2); // Only the two reactive descriptors
        workers.forEach(w ->
            assertThat(w.capabilityNames()).containsExactly("commentary-reactive"));
    }

    @Test
    void createNarrativeWorkers_filtersNarrativeCapability() {
        List<AgentDescriptor> descriptors = List.of(
            buildReactiveDescriptor("claude:commentator-energetic@v1", "Energetic Commentator", "bold"),
            buildNarrativeDescriptor("claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible"),
            buildNarrativeDescriptor("claude:narrator-tactical@v1", "Tactical Narrator", "strict")
        );
        ChatModel stubModel = stubChatModel("Over the last minute...");

        List<Worker> workers = CommentaryWorkerFactory.createNarrativeWorkers(
            descriptors, stubModel, noOpCallback());

        assertThat(workers).hasSize(2); // Only the two narrative descriptors
        workers.forEach(w ->
            assertThat(w.capabilityNames()).containsExactly("commentary-narrative"));
    }

    @Test
    void reactiveWorker_outputKey_correctStructure() {
        AgentDescriptor descriptor = buildReactiveDescriptor(
            "claude:commentator-energetic@v1", "Energetic Commentator", "bold");
        ChatModel stubModel = stubChatModel("The enemy is at the gates!");

        List<Worker> workers = CommentaryWorkerFactory.createReactiveWorkers(
            List.of(descriptor), stubModel, noOpCallback());
        assertThat(workers).hasSize(1);

        Worker worker = workers.get(0);
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) worker.function();
        Map<String, Object> input = Map.of(
            QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of(
                "gameFrame", 2240L,
                "momentTypes", List.of("BATTLE_STARTED")
            )
        );

        WorkerResult result = (WorkerResult) syncFn.fn().apply(input);

        assertThat(result.output()).containsKey("agent.commentary.reactive.text");
        assertThat(result.output().get("agent.commentary.reactive.text"))
            .isEqualTo("The enemy is at the gates!");
    }

    @Test
    void narrativeWorker_outputKey_correctStructure() {
        AgentDescriptor descriptor = buildNarrativeDescriptor(
            "claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible");
        ChatModel stubModel = stubChatModel("Over the last minute, the bot secured map control.");

        List<Worker> workers = CommentaryWorkerFactory.createNarrativeWorkers(
            List.of(descriptor), stubModel, noOpCallback());
        assertThat(workers).hasSize(1);

        Worker worker = workers.get(0);
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) worker.function();
        Map<String, Object> input = Map.of(
            QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER, Map.of(
                "gameFrame", 5000L,
                "moments", List.of()
            )
        );

        WorkerResult result = (WorkerResult) syncFn.fn().apply(input);

        assertThat(result.output()).containsKey("agent.commentary.narrative.text");
        assertThat(result.output().get("agent.commentary.narrative.text"))
            .isEqualTo("Over the last minute, the bot secured map control.");
    }

    @Test
    void reactiveWorker_systemPrompt_includesDisposition() {
        AgentDescriptor descriptor = buildReactiveDescriptor(
            "claude:commentator-energetic@v1", "Energetic Commentator", "bold");

        final List<ChatMessage>[] captured = new List[1];
        ChatModel capturingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured[0] = request.messages();
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("The enemy is advancing!"))
                    .build();
            }
        };

        List<Worker> workers = CommentaryWorkerFactory.createReactiveWorkers(
            List.of(descriptor), capturingModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        syncFn.fn().apply(Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of("gameFrame", 1000L)));

        assertThat(captured[0]).isNotNull();
        String systemText = captured[0].stream()
            .filter(m -> m instanceof dev.langchain4j.data.message.SystemMessage)
            .map(m -> ((dev.langchain4j.data.message.SystemMessage) m).text())
            .findFirst()
            .orElseThrow();
        assertThat(systemText).contains("play-by-play commentator");
        assertThat(systemText).contains("bold"); // riskAppetite
        assertThat(systemText).contains("Energetic Commentator"); // name
    }

    @Test
    void narrativeWorker_systemPrompt_instructsNotToRepeat() {
        AgentDescriptor descriptor = buildNarrativeDescriptor(
            "claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible");

        final List<ChatMessage>[] captured = new List[1];
        ChatModel capturingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured[0] = request.messages();
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("Over the last minute..."))
                    .build();
            }
        };

        List<Worker> workers = CommentaryWorkerFactory.createNarrativeWorkers(
            List.of(descriptor), capturingModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        syncFn.fn().apply(Map.of(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER, Map.of("gameFrame", 1000L)));

        assertThat(captured[0]).isNotNull();
        String systemText = captured[0].stream()
            .filter(m -> m instanceof dev.langchain4j.data.message.SystemMessage)
            .map(m -> ((dev.langchain4j.data.message.SystemMessage) m).text())
            .findFirst()
            .orElseThrow();
        assertThat(systemText).contains("color commentator");
        assertThat(systemText).contains("Do NOT repeat moments just announced");
        assertThat(systemText).contains("flexible"); // ruleFollowing
    }

    @Test
    void reactiveWorker_error_returnsFailed() {
        AgentDescriptor descriptor = buildReactiveDescriptor(
            "claude:commentator-energetic@v1", "Energetic Commentator", "bold");
        ChatModel failingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new RuntimeException("LLM service unavailable");
            }
        };

        List<Worker> workers = CommentaryWorkerFactory.createReactiveWorkers(
            List.of(descriptor), failingModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        WorkerResult result = (WorkerResult) syncFn.fn().apply(Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of()));

        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Failed.class);
    }

    @Test
    void reactiveWorker_firesCallback() {
        AgentDescriptor descriptor = buildReactiveDescriptor(
            "claude:commentator-energetic@v1", "Energetic Commentator", "bold");
        ChatModel stubModel = stubChatModel("The enemy is at the gates!");

        final String[] capturedWorkerId = new String[1];
        final String[] capturedText = new String[1];
        final CommentaryType[] capturedType = new CommentaryType[1];
        CommentaryCompletionCallback callback = (workerId, capability, gameFrame, text, type, latencyMs) -> {
            capturedWorkerId[0] = workerId;
            capturedText[0] = text;
            capturedType[0] = type;
        };

        List<Worker> workers = CommentaryWorkerFactory.createReactiveWorkers(
            List.of(descriptor), stubModel, callback);
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        syncFn.fn().apply(Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of("gameFrame", 2240L)));

        assertThat(capturedWorkerId[0]).isEqualTo("claude:commentator-energetic@v1");
        assertThat(capturedText[0]).isEqualTo("The enemy is at the gates!");
        assertThat(capturedType[0]).isEqualTo(CommentaryType.REACTIVE);
    }

    @Test
    void narrativeWorker_firesCallback() {
        AgentDescriptor descriptor = buildNarrativeDescriptor(
            "claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible");
        ChatModel stubModel = stubChatModel("Over the last minute...");

        final CommentaryType[] capturedType = new CommentaryType[1];
        CommentaryCompletionCallback callback = (workerId, capability, gameFrame, text, type, latencyMs) -> {
            capturedType[0] = type;
        };

        List<Worker> workers = CommentaryWorkerFactory.createNarrativeWorkers(
            List.of(descriptor), stubModel, callback);
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        syncFn.fn().apply(Map.of(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER, Map.of("gameFrame", 5000L)));

        assertThat(capturedType[0]).isEqualTo(CommentaryType.NARRATIVE);
    }

    // ── Helper methods ─────────────────────────────────────────────────


    @Test
    void reactiveSystemPrompt_containsPatternAssessmentGuidance() {
        AgentDescriptor descriptor = buildReactiveDescriptor(
                "claude:commentator-energetic@v1", "Energetic Commentator", "bold");
        String prompt = CommentaryWorkerFactory.buildReactiveSystemPrompt(descriptor);
        assertThat(prompt).contains("PATTERN_ASSESSMENT");
        assertThat(prompt).contains("archetype");
        assertThat(prompt).contains("confidence");
    }

    @Test
    void narrativeSystemPrompt_containsPatternAssessmentGuidance() {
        AgentDescriptor descriptor = buildNarrativeDescriptor(
                "claude:narrator-dramatic@v1", "Dramatic Narrator", "flexible");
        String prompt = CommentaryWorkerFactory.buildNarrativeSystemPrompt(descriptor);
        assertThat(prompt).contains("PATTERN_ASSESSMENT");
    }

    @Test
    void reactiveUserMessage_includesPatternAssessment_whenPresent() {
        String msg = CommentaryWorkerFactory.buildReactiveUserMessage(
                Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of(
                        "gameFrame", 1500L,
                        "patternAssessment", Map.of(
                                "archetype", "ZERG_ROACH_RUSH",
                                "confidence", 0.72))));
        assertThat(msg).contains("ENEMY PATTERN: ZERG_ROACH_RUSH");
        assertThat(msg).contains("0.72");
    }

    @Test
    void reactiveUserMessage_omitsPatternAssessment_whenAbsent() {
        String msg = CommentaryWorkerFactory.buildReactiveUserMessage(
                Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, Map.of("gameFrame", 1500L)));
        assertThat(msg).doesNotContain("ENEMY PATTERN");
    }

    @Test
    void narrativeUserMessage_includesPatternAssessment_whenPresent() {
        String msg = CommentaryWorkerFactory.buildNarrativeUserMessage(
                Map.of(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER, Map.of(
                        "patternAssessment", Map.of(
                                "archetype", "TERRAN_MARINE_RUSH",
                                "confidence", 0.85))));
        assertThat(msg).contains("ENEMY PATTERN: TERRAN_MARINE_RUSH");
        assertThat(msg).contains("0.85");
    }

    private static CommentaryCompletionCallback noOpCallback() {
        return (workerId, capability, gameFrame, text, type, latencyMs) -> {
            // No-op for tests that don't care about completion events
        };
    }

    private static ChatModel stubChatModel(String responseText) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responseText))
                    .build();
            }
        };
    }

    private static AgentDescriptor buildReactiveDescriptor(String agentId, String name, String riskAppetite) {
        return AgentDescriptor.builder()
            .agentId(agentId)
            .name(name)
            .provider("anthropic")
            .modelFamily("claude")
            .modelVersion("sonnet-4")
            .slot("reactive-commentator")
            .slotVocabulary("urn:casehub:vocab:conscientiousness")
            .disposition(AgentDisposition.builder()
                .riskAppetite(riskAppetite)
                .ruleFollowing("flexible")
                .build())
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("commentary-reactive")
                    .latencyHintP50Ms(1500L)
                    .qualityHint(0.7)
                    .tags(List.of("starcraft.commentary.reactive"))
                    .build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }

    private static AgentDescriptor buildNarrativeDescriptor(String agentId, String name, String ruleFollowing) {
        return AgentDescriptor.builder()
            .agentId(agentId)
            .name(name)
            .provider("anthropic")
            .modelFamily("claude")
            .modelVersion("sonnet-4")
            .slot("narrative-commentator")
            .slotVocabulary("urn:casehub:vocab:conscientiousness")
            .disposition(AgentDisposition.builder()
                .riskAppetite("conservative")
                .ruleFollowing(ruleFollowing)
                .build())
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("commentary-narrative")
                    .latencyHintP50Ms(3000L)
                    .qualityHint(0.8)
                    .tags(List.of("starcraft.commentary.narrative"))
                    .build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }
}
