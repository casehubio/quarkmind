package io.quarkmind.agent;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.worker.api.WorkerScope;
import io.quarkmind.plugin.advisory.AdvisoryWorkerFactory;
import io.quarkmind.plugin.advisory.CompletionCallback;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import io.quarkmind.plugin.coaching.CoachingCompleted;
import io.quarkmind.plugin.coaching.CoachingCompletionCallback;
import io.quarkmind.plugin.coaching.CoachingWorkerFactory;
import io.quarkmind.plugin.commentary.CommentaryCompleted;
import io.quarkmind.plugin.commentary.CommentaryCompletionCallback;
import io.quarkmind.plugin.commentary.CommentaryWorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CaseHub subclass defining the {@code starcraft-game} case type for the casehub-engine.
 *
 * <p>Discovers all {@link TaskDefinition} implementations via CDI and assembles a
 * {@link CaseDefinition} with:
 * <ul>
 *   <li>A {@code tick-decision} capability and binding — fires on every game-frame change</li>
 *   <li>A {@code strategy} capability — three competing strategy implementations routed
 *       by {@code ImplementationRoutingStrategy}</li>
 * </ul>
 *
 * <p>The parent {@link CaseHub} provides {@code startCase()}, {@code signal()}, and
 * {@code cancelCase()} via its injected {@code CaseHubRuntime}. This subclass adds
 * {@link #signalAndAwaitSync} for synchronous per-tick dispatch — the runtime's
 * {@code signalAndAwaitSync} blocks until all triggered workers settle.
 *
 * <p><b>Lazy plugin resolution:</b> The engine calls {@code getDefinition()} from a Vert.x
 * IO thread during {@code DefaultCaseDefinitionRegistry.registerKnownDefinitions()}. Eagerly
 * creating all {@link TaskDefinition} CDI beans during that callback would trigger
 * {@code @PostConstruct} methods that do blocking JPA (e.g. {@code ScoutingIntelBroker.init()})
 * and SmallRye channel wiring (e.g. {@code FlowEconomicsTask.emitter}) — both illegal on an
 * IO thread. The CDI {@code Instance<TaskDefinition>} is stored without resolution;
 * {@link #resolveTickChain()} materializes the beans lazily on first call (from a worker thread).
 *
 * <p>Refs #207
 */
@ApplicationScoped
public class QuarkMindCaseHub extends CaseHub {

    private static final Logger log = Logger.getLogger(QuarkMindCaseHub.class);

    /** JQ expression that fires on every game-frame change in the working panel. */
    static final String TICK_TRIGGER = ".working[\"game.frame\"] | . != null";

    private static final String CAPABILITY_TICK_DECISION = "tick-decision";
    private static final String CAPABILITY_STRATEGY = "strategy";

    // Advisory capability names — must match QuarkMindAdvisorRegistrar capability names
    static final String CAPABILITY_ADVISORY_CRISIS = "advisory-crisis";
    static final String CAPABILITY_ADVISORY_STRATEGIC = "advisory-strategic";
    static final String CAPABILITY_ADVISORY_ECONOMIC = "advisory-economic";

    /** JQ expression that fires when a crisis advisory trigger is set. */
    static final String CRISIS_TRIGGER = ".working[\"game.advisory.trigger.crisis\"] | . != null";
    /** JQ expression that fires when a strategic advisory trigger is set. */
    static final String STRATEGIC_TRIGGER = ".working[\"game.advisory.trigger.strategic\"] | . != null";
    /** JQ expression that fires when an economic advisory trigger is set. */
    static final String ECONOMIC_TRIGGER = ".working[\"game.advisory.trigger.economic\"] | . != null";

    // Commentary capability names — must match QuarkMindAgentRegistrar capability names
    static final String CAPABILITY_COMMENTARY_REACTIVE = "commentary-reactive";
    static final String CAPABILITY_COMMENTARY_NARRATIVE = "commentary-narrative";

    /** JQ expression that fires when a reactive commentary trigger is set. */
    static final String REACTIVE_TRIGGER = ".working[\"game.commentary.trigger\"] | . != null";
    /** JQ expression that fires when a narrative commentary trigger is set. */
    static final String NARRATIVE_TRIGGER = ".working[\"game.commentary.narrative.trigger\"] | . != null";

    // Coaching capability name — must match QuarkMindAgentRegistrar capability name
    static final String CAPABILITY_COACHING = "coaching";

    /** JQ expression that fires when a coaching trigger is set. */
    static final String COACHING_TRIGGER = ".working[\"game.coaching.trigger\"] | . != null";

    private static final List<String> PHASE_ORDER = List.of(
            "scouting.",           // Phase 1: observe
            "strategy-routing.",   // Phase 2a: route (select which strategy — CBR + trust)
            "strategy.",           // Phase 2b: decide (selected strategy executes)
            "tactics.",            // Phase 3: act
            "economics.",          // Phase 4: build
            "summarisation."       // Phase 5: reflect
                                                           );

    /**
     * CDI Instance — lazy handle. Not resolved in the constructor because the engine calls
     * {@code getDefinition()} from a Vert.x IO thread. Resolved on first call to
     * {@link #resolveTickChain()} (from a worker thread).
     */
    private final Instance<TaskDefinition> taskDefInstance;

    /** Lazily materialized plugin list — null until {@link #resolveTickChain()} is called. */
    private volatile List<TaskDefinition> plugins;

    /** Advisory registrar — provides AgentDescriptors for LLM advisory workers. */
    private final QuarkMindAgentRegistrar advisorRegistrar;

    /**
     * Optional ChatModel — graceful degradation when no LLM is configured.
     * Uses {@code Instance<ChatModel>} so advisory workers are omitted when no
     * ChatModel bean exists (e.g. mock/test profiles without an LLM backend).
     */
    private final Instance<ChatModel> chatModelInstance;

    /**
     * CDI event for advisory completion — fired by advisory Workers via the
     * completion callback passed to {@link AdvisoryWorkerFactory}.
     */
    private final Instance<Event<AdvisoryCompleted>> advisoryCompletedEventInstance;

    /**
     * CDI event for LLM Worker completion — fired by both advisory and commentary
     * Workers for shared latency recording.
     */
    private final Instance<Event<LlmWorkerCompleted>> llmWorkerCompletedEventInstance;

    /**
     * CDI event for commentary completion — fired by commentary Workers via the
     * completion callback passed to {@link CommentaryWorkerFactory}.
     */
    private final Instance<Event<CommentaryCompleted>> commentaryCompletedEventInstance;

    private final Instance<Event<CoachingCompleted>> coachingCompletedEventInstance;

    /**
     * Injected separately because the parent's {@code runtime} field is package-private
     * ({@code io.casehub.api.engine}) and inaccessible from this package. Used for
     * {@link #signalAndAwaitSync} which has no delegation method on {@link CaseHub}.
     */
    @Inject
    CaseHubRuntime caseHubRuntime;
    @jakarta.inject.Inject
    StrategyTaxonomy strategyTaxonomy;


    /**
     * CDI constructor — stores the Instance handles without resolving beans.
     * Bean creation is deferred to {@link #resolveTickChain()} to avoid blocking
     * operations on the Vert.x IO thread during engine registration.
     */
    @Inject
    QuarkMindCaseHub(@Any Instance<TaskDefinition> allTaskDefs,
                     QuarkMindAgentRegistrar advisorRegistrar,
                     Instance<ChatModel> chatModelInstance,
                     Instance<Event<AdvisoryCompleted>> advisoryCompletedEventInstance,
                     Instance<Event<LlmWorkerCompleted>> llmWorkerCompletedEventInstance,
                     Instance<Event<CommentaryCompleted>> commentaryCompletedEventInstance,
                     Instance<Event<CoachingCompleted>> coachingCompletedEventInstance) {
        this.taskDefInstance = allTaskDefs;
        this.plugins = null; // resolved lazily
        this.advisorRegistrar = advisorRegistrar;
        this.chatModelInstance = chatModelInstance;
        this.advisoryCompletedEventInstance = advisoryCompletedEventInstance;
        this.llmWorkerCompletedEventInstance = llmWorkerCompletedEventInstance;
        this.commentaryCompletedEventInstance = commentaryCompletedEventInstance;
        this.coachingCompletedEventInstance = coachingCompletedEventInstance;
    }

    /**
     * Test constructor — accepts an explicit plugin list (no CDI).
     */
    QuarkMindCaseHub(List<TaskDefinition> plugins) {
        this.taskDefInstance = null; // not used in tests
        this.plugins = List.copyOf(plugins);
        this.advisorRegistrar = null;
        this.chatModelInstance = null;
        this.advisoryCompletedEventInstance = null;
        this.llmWorkerCompletedEventInstance = null;
        this.commentaryCompletedEventInstance = null;
        this.coachingCompletedEventInstance = null;
        log.infof("[CASEHUB] Discovered %d TaskDefinition implementations", this.plugins.size());
    }

    /**
     * Synchronous bulk signal — applies all updates atomically, dispatches triggered workers,
     * and blocks until settlement. Used by {@link GameTickExecutor} for per-tick dispatch.
     *
     * <p>Delegates to {@link CaseHubRuntime#signalAndAwaitSync(UUID, Map, Duration)}.
     *
     * @param caseId  the active game session case
     * @param updates flat key map from {@link GameStateTranslator#toMap}
     * @param timeout maximum wait for worker settlement
     * @return the settled CaseContext
     */
    public CaseContext signalAndAwaitSync(UUID caseId, Map<String, Object> updates, Duration timeout) {
        return caseHubRuntime.signalAndAwait(caseId, updates, timeout);
    }

    /**
     * Async bulk signal — applies updates and triggers bindings without waiting for settlement.
     * Used for fire-and-forget advisory triggers (two-signal pattern).
     */
    public void signal(UUID caseId, Map<String, Object> updates) {
        caseHubRuntime.signal(caseId, updates);
    }

    @Override
    public CaseDefinition getDefinition() {
        Capability tickDecision = Capability.builder()
            .name(CAPABILITY_TICK_DECISION)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Per-tick game loop orchestration: scouting → strategy → tactics → economics")
            .build();

        Capability strategy = Capability.builder()
            .name(CAPABILITY_STRATEGY)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Strategy implementation — competing Workers routed by ImplementationRoutingStrategy")
            .build();

        // Tick orchestrator: lazy function — resolves plugins on first invocation (worker thread),
        // not during getDefinition() (Vert.x IO thread). This avoids triggering CDI bean creation
        // for TaskDefinition impls that have blocking @PostConstruct or SmallRye emitters.
        Worker tickOrchestrator = Worker.builder()
            .name("tick-orchestrator")
            .capabilityName(CAPABILITY_TICK_DECISION)
            .function(new WorkerFunction.Sync<>(Map.class, Map.class, (Map input, WorkerScope scope) -> {
                List<TaskDefinition> chain = resolveTickChain();
                return (WorkerResult) TickOrchestratorWorker.createFunction(chain).fn().apply(input, scope);
            }))
            .description("Chains plugin execution: scouting → strategy → tactics → economics")
            .build();

        Binding tickBinding = Binding.builder()
            .name(CAPABILITY_TICK_DECISION)
            .capability(tickDecision)
            .on(new ContextChangeTrigger(TICK_TRIGGER))
            .build();

        // Collect all capabilities and bindings (tick + advisory)
        List<Capability> allCapabilities = new ArrayList<>();
        allCapabilities.add(tickDecision);
        allCapabilities.add(strategy);

        List<Binding> allBindings = new ArrayList<>();
        allBindings.add(tickBinding);

        List<Worker> allWorkers = new ArrayList<>();
        allWorkers.add(tickOrchestrator);

        // Advisory capabilities, bindings, and workers — only when a ChatModel is available
        int advisoryCount = wireAdvisory(allCapabilities, allBindings, allWorkers);

        // Commentary capabilities, bindings, and workers — only when a ChatModel is available
        int commentaryCount = wireCommentary(allCapabilities, allBindings, allWorkers);

        // Coaching capabilities, bindings, and workers — only when a ChatModel is available
        int coachingCount = wireCoaching(allCapabilities, allBindings, allWorkers);

        CaseDefinition.Builder builder = CaseDefinition.builder()
            .namespace("quarkmind")
            .name("starcraft-game")
            .version("1.0")
            .capabilities(allCapabilities)
            .bindings(allBindings)
            .workers(allWorkers);

        // Register AgentDescriptors on the CaseDefinition for routing
        int llmWorkerCount = advisoryCount + commentaryCount + coachingCount;
        if (llmWorkerCount > 0 && advisorRegistrar != null) {
            for (AgentDescriptor descriptor : advisorRegistrar.descriptors()) {
                builder.agentDescriptor(descriptor.agentId(), descriptor);
            }
        }

        log.infof("[CASEHUB] Built CaseDefinition: %d capabilities, %d workers, %d bindings (advisory: %d, commentary: %d, coaching: %d)",
            allCapabilities.size(), allWorkers.size(), allBindings.size(), advisoryCount, commentaryCount, coachingCount);

        return builder.build();
    }

    /**
     * Wires advisory capabilities, bindings, and workers into the CaseDefinition.
     *
     * <p>Graceful degradation: if no ChatModel bean is available (e.g. mock/test profiles
     * without an LLM backend), advisory workers are omitted and a warning is logged.
     *
     * @return number of advisory workers added (0 if no ChatModel available)
     */
    private int wireAdvisory(List<Capability> capabilities, List<Binding> bindings,
                             List<Worker> workers) {
        if (chatModelInstance == null || !chatModelInstance.isResolvable()) {
            log.warn("[CASEHUB] No ChatModel bean available — advisory workers omitted. "
                    + "Configure an LLM provider (e.g. quarkus-langchain4j-anthropic) to enable advisory.");
            return 0;
        }
        if (advisorRegistrar == null) {
            log.warn("[CASEHUB] No QuarkMindAdvisorRegistrar available — advisory workers omitted.");
            return 0;
        }

        // Advisory capabilities
        Capability advisoryCrisis = Capability.builder()
            .name(CAPABILITY_ADVISORY_CRISIS)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Crisis advisory — responds to NEXUS_UNDER_ATTACK, BATTLE_STARTED")
            .build();

        Capability advisoryStrategic = Capability.builder()
            .name(CAPABILITY_ADVISORY_STRATEGIC)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Strategic advisory — responds to PHASE_TRANSITION, STRATEGY_SHIFT")
            .build();

        Capability advisoryEconomic = Capability.builder()
            .name(CAPABILITY_ADVISORY_ECONOMIC)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Economic advisory — responds to EXPANSION_WINDOW, INCOME_DROP")
            .build();

        capabilities.add(advisoryCrisis);
        capabilities.add(advisoryStrategic);
        capabilities.add(advisoryEconomic);

        // Advisory bindings — fire on trigger keys
        bindings.add(Binding.builder()
            .name(CAPABILITY_ADVISORY_CRISIS)
            .capability(advisoryCrisis)
            .on(new ContextChangeTrigger(CRISIS_TRIGGER))
            .build());

        bindings.add(Binding.builder()
            .name(CAPABILITY_ADVISORY_STRATEGIC)
            .capability(advisoryStrategic)
            .on(new ContextChangeTrigger(STRATEGIC_TRIGGER))
            .build());

        bindings.add(Binding.builder()
            .name(CAPABILITY_ADVISORY_ECONOMIC)
            .capability(advisoryEconomic)
            .on(new ContextChangeTrigger(ECONOMIC_TRIGGER))
            .build());

        // Advisory workers from factory
        ChatModel chatModel = chatModelInstance.get();
        Event<AdvisoryCompleted> completedEvent = advisoryCompletedEventInstance.get();
        Event<LlmWorkerCompleted> llmWorkerCompletedEvent = llmWorkerCompletedEventInstance.get();

        // Completion callback — fires both AdvisoryCompleted (domain-specific) and
        // LlmWorkerCompleted (shared latency recording) CDI events
        CompletionCallback completionCallback = (advisorId, capability, gameFrame,
                                                 recommendation, confidence, latencyMs, gameStateSnapshot) -> {
            completedEvent.fire(new AdvisoryCompleted(
                advisorId, capability, gameFrame, recommendation, confidence, latencyMs, gameStateSnapshot
            ));
            llmWorkerCompletedEvent.fire(new LlmWorkerCompleted(
                advisorId, capability, gameFrame, latencyMs
            ));
        };

        List<Worker> advisoryWorkers = AdvisoryWorkerFactory.createWorkers(
                advisorRegistrar.descriptors(), chatModel, completionCallback);
        workers.addAll(advisoryWorkers);

        log.infof("[CASEHUB] Wired %d advisory workers across 3 capabilities", advisoryWorkers.size());
        return advisoryWorkers.size();
    }

    /**
     * Wires commentary capabilities, bindings, and workers into the CaseDefinition.
     *
     * <p>Graceful degradation: if no ChatModel bean is available (e.g. mock/test profiles
     * without an LLM backend), commentary workers are omitted and a warning is logged.
     *
     * @return number of commentary workers added (0 if no ChatModel available)
     */
    private int wireCommentary(List<Capability> capabilities, List<Binding> bindings,
                               List<Worker> workers) {
        if (chatModelInstance == null || !chatModelInstance.isResolvable()) {
            log.warn("[CASEHUB] No ChatModel bean available — commentary workers omitted. "
                    + "Configure an LLM provider (e.g. quarkus-langchain4j-anthropic) to enable commentary.");
            return 0;
        }
        if (advisorRegistrar == null) {
            log.warn("[CASEHUB] No QuarkMindAgentRegistrar available — commentary workers omitted.");
            return 0;
        }

        // Commentary capabilities
        Capability commentaryReactive = Capability.builder()
            .name(CAPABILITY_COMMENTARY_REACTIVE)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Reactive commentary — responds to all GameMoment types")
            .build();

        Capability commentaryNarrative = Capability.builder()
            .name(CAPABILITY_COMMENTARY_NARRATIVE)
            .inputSchema(".working")
            .outputSchema(".")
            .description("Narrative commentary — periodic strategic summaries")
            .build();

        capabilities.add(commentaryReactive);
        capabilities.add(commentaryNarrative);

        // Commentary bindings — fire on trigger keys
        bindings.add(Binding.builder()
            .name(CAPABILITY_COMMENTARY_REACTIVE)
            .capability(commentaryReactive)
            .on(new ContextChangeTrigger(REACTIVE_TRIGGER))
            .build());

        bindings.add(Binding.builder()
            .name(CAPABILITY_COMMENTARY_NARRATIVE)
            .capability(commentaryNarrative)
            .on(new ContextChangeTrigger(NARRATIVE_TRIGGER))
            .build());

        // Commentary workers from factory
        ChatModel chatModel = chatModelInstance.get();
        Event<CommentaryCompleted> commentaryCompletedEvent = commentaryCompletedEventInstance.get();
        Event<LlmWorkerCompleted> llmWorkerCompletedEvent = llmWorkerCompletedEventInstance.get();

        // Completion callback — fires both CommentaryCompleted (domain-specific) and
        // LlmWorkerCompleted (shared latency recording) CDI events
        CommentaryCompletionCallback completionCallback = (workerId, capability, gameFrame,
                                                           text, commentaryType, latencyMs) -> {
            commentaryCompletedEvent.fire(new CommentaryCompleted(
                workerId, capability, gameFrame, text, commentaryType, latencyMs
            ));
            llmWorkerCompletedEvent.fire(new LlmWorkerCompleted(
                workerId, capability, gameFrame, latencyMs
            ));
        };

        List<Worker> reactiveWorkers = CommentaryWorkerFactory.createReactiveWorkers(
                advisorRegistrar.descriptors(), chatModel, completionCallback);
        workers.addAll(reactiveWorkers);

        List<Worker> narrativeWorkers = CommentaryWorkerFactory.createNarrativeWorkers(
                advisorRegistrar.descriptors(), chatModel, completionCallback);
        workers.addAll(narrativeWorkers);

        int totalCommentaryWorkers = reactiveWorkers.size() + narrativeWorkers.size();
        log.infof("[CASEHUB] Wired %d commentary workers across 2 capabilities", totalCommentaryWorkers);
        return totalCommentaryWorkers;
    }

    private int wireCoaching(List<Capability> capabilities, List<Binding> bindings,
                             List<Worker> workers) {
        if (chatModelInstance == null || !chatModelInstance.isResolvable()) {
            log.warn("[CASEHUB] No ChatModel bean available — coaching workers omitted.");
            return 0;
        }
        if (advisorRegistrar == null) {
            log.warn("[CASEHUB] No QuarkMindAgentRegistrar available — coaching workers omitted.");
            return 0;
        }

        Capability coaching = Capability.builder()
                                        .name(CAPABILITY_COACHING)
                                        .inputSchema(".working")
                                        .outputSchema(".")
                                        .description("Coaching — real-time actionable advice for human players")
                                        .build();

        capabilities.add(coaching);

        bindings.add(Binding.builder()
                            .name(CAPABILITY_COACHING)
                            .capability(coaching)
                            .on(new ContextChangeTrigger(COACHING_TRIGGER))
                            .build());

        ChatModel                 chatModel               = chatModelInstance.get();
        Event<CoachingCompleted>  coachingCompletedEvent  = coachingCompletedEventInstance.get();
        Event<LlmWorkerCompleted> llmWorkerCompletedEvent = llmWorkerCompletedEventInstance.get();

        CoachingCompletionCallback completionCallback = (workerId, capability, gameFrame,
                                                         advice, urgencyTier, latencyMs, triggerState) -> {
            coachingCompletedEvent.fire(new CoachingCompleted(
                    workerId, capability, gameFrame, advice, urgencyTier, latencyMs, triggerState
            ));
            llmWorkerCompletedEvent.fire(new LlmWorkerCompleted(
                    workerId, capability, gameFrame, latencyMs
            ));
        };

        List<Worker> coachingWorkers = CoachingWorkerFactory.createWorkers(
                advisorRegistrar.descriptors(), chatModel, completionCallback, strategyTaxonomy);
        workers.addAll(coachingWorkers);

        log.infof("[CASEHUB] Wired %d coaching workers", coachingWorkers.size());
        return coachingWorkers.size();
    }


    /**
     * Resolves the tick plugin chain in execution order.
     *
     * <p>All discovered {@link TaskDefinition} implementations are ordered by their phase
     * prefix (scouting → strategy → tactics → economics → summarisation). Plugins whose
     * ID prefix is not in {@link #PHASE_ORDER} are appended at the end — they still
     * participate, but run after the known phases.
     *
     * <p>Strategy plugins ARE included in the tick chain. During Phase 1, their
     * {@code activateIf()} predicates gate which one runs. In Phase 2, strategy routing
     * moves to the engine's {@code ImplementationRoutingStrategy} and strategy plugins
     * are removed from the tick chain.
     *
     * <p><b>Thread safety:</b> Lazily materializes the plugin list from CDI {@code Instance<>}
     * on first call. Must be called from a worker thread (not a Vert.x IO thread) because
     * bean creation triggers blocking operations ({@code @PostConstruct} JPA, SmallRye channels).
     */
    List<TaskDefinition> resolveTickChain() {
        List<TaskDefinition> resolved = plugins;
        if (resolved == null) {
            synchronized (this) {
                if (plugins == null) {
                    plugins = taskDefInstance.stream()
                        .sorted(Comparator.comparingInt(td -> phaseIndex(td.getId())))
                        .toList();
                    log.infof("[CASEHUB] Resolved %d TaskDefinition implementations (lazy)", plugins.size());
                }
                resolved = plugins;
            }
        }
        return resolved;
    }

    private static int phaseIndex(String pluginId) {
        for (int i = 0; i < PHASE_ORDER.size(); i++) {
            if (pluginId.startsWith(PHASE_ORDER.get(i))) {
                return i;
            }
        }
        return PHASE_ORDER.size(); // unknown prefix → after all known phases
    }
}
