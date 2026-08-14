package io.quarkmind.plugin.advisory;

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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdvisoryWorkerFactory}.
 *
 * <p>Verifies the factory correctly translates AgentDescriptors into Workers that:
 * <ul>
 *   <li>Use the descriptor's agentId as the Worker name</li>
 *   <li>Use the descriptor's first capability name as the Worker capabilityName</li>
 *   <li>Produce the correct output key structure from the WorkerFunction</li>
 * </ul>
 *
 * <p>Uses a stub ChatModel that returns a fixed response — no real LLM calls.
 */
class AdvisoryWorkerFactoryTest {

    @Test
    void creates_six_workers_from_six_advisory_descriptors() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();
        ChatModel               stubModel = stubChatModel("Recommendation text");

        // Filter to only advisory descriptors
        List<AgentDescriptor> advisoryDescriptors = registrar.descriptors().stream()
                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().startsWith("advisory-")))
                .toList();

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                advisoryDescriptors, stubModel, noOpCallback());

        assertThat(workers).hasSize(6);
    }

    @Test
    void worker_names_match_descriptor_agent_ids() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();
        ChatModel               stubModel = stubChatModel("Recommendation text");

        // Filter to only advisory descriptors
        List<AgentDescriptor> advisoryDescriptors = registrar.descriptors().stream()
                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().startsWith("advisory-")))
                .toList();

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                advisoryDescriptors, stubModel, noOpCallback());

        List<String> workerNames = workers.stream().map(Worker::name).toList();
        List<String> descriptorIds = advisoryDescriptors.stream()
                .map(AgentDescriptor::agentId).toList();

        assertThat(workerNames).containsExactlyElementsOf(descriptorIds);
    }

    @Test
    void worker_capability_names_match_descriptor_capabilities() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();
        ChatModel               stubModel = stubChatModel("Recommendation text");

        // Filter to only advisory descriptors
        List<AgentDescriptor> advisoryDescriptors = registrar.descriptors().stream()
                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().startsWith("advisory-")))
                .toList();

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                advisoryDescriptors, stubModel, noOpCallback());

        // Each worker's capabilityNames set should contain the descriptor's first capability name
        for (int i = 0; i < workers.size(); i++) {
            Worker worker = workers.get(i);
            AgentDescriptor descriptor = advisoryDescriptors.get(i);
            String expectedCapability = descriptor.capabilities().get(0).name();
            assertThat(worker.capabilityNames())
                    .as("Worker %s capability", worker.name())
                    .containsExactly(expectedCapability);
        }
    }

    @Test
    void crisis_worker_function_returns_correct_output_keys() {
        AgentDescriptor crisisDescriptor = buildDescriptor(
                "claude:crisis-aggressive@v1", "Aggressive Crisis Responder",
                "crisis-responder", "advisory-crisis",
                AgentDisposition.builder()
                        .socialOrient("collaborative")
                        .ruleFollowing("flexible")
                        .riskAppetite("bold")
                        .autonomy("semi-autonomous")
                        .conflictMode("compete")
                        .delegation(false)
                        .build());

        String llmResponse = "RECOMMENDATION: Pull probes to defend.\n"
                + "REASONING: Enemy has committed significant army. Counter-attack is risky.\n"
                + "CONFIDENCE: 0.85";
        ChatModel stubModel = stubChatModel(llmResponse);

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                List.of(crisisDescriptor), stubModel, noOpCallback());
        assertThat(workers).hasSize(1);

        Worker crisisWorker = workers.get(0);
        assertThat(crisisWorker.function()).isInstanceOf(WorkerFunction.Sync.class);

        // Execute the worker function with a sample game state input
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) crisisWorker.function();
        Map<String, Object> input = Map.of(
                "game.frame", 2240,
                "game.resources.minerals", 300,
                "game.resources.vespene", 150,
                "game.advisory.trigger.crisis", "NEXUS_UNDER_ATTACK"
        );

        WorkerResult result = (WorkerResult) syncFn.fn().apply(input, null);

        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.crisis.recommendation");
        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.crisis.reasoning");
        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.crisis.confidence");
        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.crisis.agent_id");
        assertThat(((Map<String, Object>) result.output()).get("agent.advisory.crisis.agent_id"))
                .isEqualTo("claude:crisis-aggressive@v1");
    }

    @Test
    void strategic_worker_function_returns_correct_role_prefix() {
        AgentDescriptor strategicDescriptor = buildDescriptor(
                "claude:strategic-bold@v1", "Bold Strategic Advisor",
                "strategic-advisor", "advisory-strategic",
                AgentDisposition.builder()
                        .socialOrient("collaborative")
                        .ruleFollowing("flexible")
                        .riskAppetite("bold")
                        .autonomy("semi-autonomous")
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build());

        ChatModel stubModel = stubChatModel("RECOMMENDATION: Expand to third base.");

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                List.of(strategicDescriptor), stubModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        Map<String, Object> input = Map.of(
                "game.frame", 5000,
                "game.advisory.trigger.strategic", "PHASE_TRANSITION"
        );

        WorkerResult result = (WorkerResult) syncFn.fn().apply(input, null);

        // Strategic role → keys prefixed with agent.advisory.strategic.*
        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.strategic.recommendation");
        assertThat((Map<String, Object>) result.output()).containsKey("agent.advisory.strategic.agent_id");
        assertThat(((Map<String, Object>) result.output()).get("agent.advisory.strategic.agent_id"))
                .isEqualTo("claude:strategic-bold@v1");
    }

    @Test
    void worker_function_returns_failed_result_on_llm_error() {
        AgentDescriptor descriptor = buildDescriptor(
                "claude:crisis-conservative@v1", "Conservative Crisis Responder",
                "crisis-responder", "advisory-crisis",
                AgentDisposition.builder()
                        .socialOrient("collaborative")
                        .ruleFollowing("strict")
                        .riskAppetite("conservative")
                        .autonomy("semi-autonomous")
                        .conflictMode("compete")
                        .delegation(false)
                        .build());

        ChatModel failingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new RuntimeException("LLM service unavailable");
            }
        };

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                List.of(descriptor), failingModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        Map<String, Object> input = Map.of("game.frame", 1000);
        WorkerResult result = (WorkerResult) syncFn.fn().apply(input, null);

        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Failed.class);
    }

    @Test
    void system_prompt_includes_disposition_traits() {
        AgentDescriptor descriptor = buildDescriptor(
                "claude:economic-expansion@v1", "Expansion Economic Optimizer",
                "economic-optimizer", "advisory-economic",
                AgentDisposition.builder()
                        .socialOrient("collaborative")
                        .ruleFollowing("flexible")
                        .riskAppetite("bold")
                        .autonomy("semi-autonomous")
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build());

        // Capture the messages sent to the ChatModel
        final List<ChatMessage>[] captured = new List[1];
        ChatModel capturingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured[0] = request.messages();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("RECOMMENDATION: Expand aggressively."))
                        .build();
            }
        };

        List<Worker> workers = AdvisoryWorkerFactory.createWorkers(
                List.of(descriptor), capturingModel, noOpCallback());
        WorkerFunction.Sync syncFn = (WorkerFunction.Sync) workers.get(0).function();

        syncFn.fn().apply(Map.of("game.frame", 3000, "game.advisory.trigger.economic", "EXPANSION_WINDOW"), null);

        assertThat(captured[0]).isNotNull();
        // System message should mention disposition traits
        String systemText = captured[0].stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.SystemMessage)
                .map(m -> ((dev.langchain4j.data.message.SystemMessage) m).text())
                .findFirst()
                .orElseThrow();
        assertThat(systemText).contains("bold");       // riskAppetite
        assertThat(systemText).contains("flexible");   // ruleFollowing
        assertThat(systemText).contains("economic");   // role name
    }

    // ── Helper methods ─────────────────────────────────────────────────


    @Test
    void system_prompt_references_pattern_assessment() {
        AgentDescriptor descriptor = buildDescriptor(
                "claude:crisis-aggressive@v1", "Aggressive Crisis Advisor",
                "crisis-response", "advisory-crisis",
                AgentDisposition.builder()
                                .socialOrient("collaborative")
                                .ruleFollowing("flexible")
                                .riskAppetite("bold")
                                .autonomy("semi-autonomous")
                                .conflictMode("collaborate")
                                .delegation(false)
                                .build());
        String prompt = AdvisoryWorkerFactory.buildSystemPrompt(descriptor, "crisis");
        assertThat(prompt).contains("PATTERN_ASSESSMENT");
        assertThat(prompt).contains("archetype");
    }

    @Test
    void user_message_includes_pattern_assessment_when_present() {
        String msg = AdvisoryWorkerFactory.buildUserMessage("crisis",
                                                            Map.of("game.advisory.trigger.crisis", Map.of(
                                                                           "event", "TIMING_ALERT",
                                                                           "patternAssessment", Map.of(
                                                                                   "archetype", "ZERG_ROACH_RUSH",
                                                                                   "confidence", 0.72)),
                                                                   "game.frame", 3000));
        assertThat(msg).contains("Enemy pattern classification: ZERG_ROACH_RUSH");
        assertThat(msg).contains("0.72");
    }

    @Test
    void user_message_omits_pattern_assessment_when_absent() {
        String msg = AdvisoryWorkerFactory.buildUserMessage("crisis",
                                                            Map.of("game.advisory.trigger.crisis", "TIMING_ALERT",
                                                                   "game.frame", 3000));
        assertThat(msg).doesNotContain("Enemy pattern classification");
    }

    private static CompletionCallback noOpCallback() {
        return (advisorId, capability, gameFrame, recommendation, confidence, latencyMs, gameStateSnapshot) -> {
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

    private static AgentDescriptor buildDescriptor(
            String agentId, String name, String slot, String capabilityName,
            AgentDisposition disposition) {
        return AgentDescriptor.builder()
                .agentId(agentId)
                .name(name)
                .provider("anthropic")
                .modelFamily("claude")
                .modelVersion("sonnet-4")
                .slot(slot)
                .slotVocabulary("urn:casehub:vocab:conscientiousness")
                .disposition(disposition)
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name(capabilityName)
                                .latencyHintP50Ms(1500L)
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory." + capabilityName.replace("advisory-", "")))
                                .build()))
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                .build();
    }
}
