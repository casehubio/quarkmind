package io.quarkmind.agent;

import io.casehub.core.TaskDefinitionRegistry;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.sc2.GameStarted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the quarkmind-plugin-dispatch qhorus channel.
 *
 * <p>Emits COMMAND+DONE or COMMAND+DECLINE on plugin activation transitions.
 * Called by GameTickExecutor before createAndSolve() — pre-engine, on the tick snapshot.
 *
 * <p>Signal frequency: transitions only. First tick after GameStarted establishes baseline.
 * Signals only fire when a plugin's activation state changes (or is first observed).
 *
 * <p>Phase 1 semantic compromise: DONE is dispatched before the plugin executes.
 * Phase 2 SequenceWorker (engine#484) will emit DONE post-execution.
 *
 * <p>Refs #199
 */
@ApplicationScoped
public class PluginDispatchBroker {

    public static final String CHANNEL_NAME = "quarkmind-plugin-dispatch";

    private static final Logger log = Logger.getLogger(PluginDispatchBroker.class);

    private final TaskDefinitionRegistry registry;
    private final MessageService         messageService;
    private final ChannelService         channelService;

    private final ConcurrentHashMap<String, Boolean> priorActivation = new ConcurrentHashMap<>();
    private volatile long lastDispatchedId = 0L;

    private UUID channelId;

    @Inject
    public PluginDispatchBroker(TaskDefinitionRegistry registry,
                                 MessageService messageService,
                                 ChannelService channelService) {
        this.registry       = registry;
        this.messageService = messageService;
        this.channelService = channelService;
    }

    /** Package-private — unit tests; bypasses @PostConstruct channel setup. channelId must be non-null. */
    PluginDispatchBroker(TaskDefinitionRegistry registry, MessageService messageService, UUID channelId) {
        this.registry       = registry;
        this.messageService = messageService;
        this.channelService = null;   // only used in @PostConstruct
        this.channelId      = channelId;
    }

    @PostConstruct
    void init() {
        // GE-20260529-88b7b6: @Transactional on @PostConstruct not intercepted by Arc;
        // ChannelService.create() is not idempotent — findByName() first.
        channelId = QuarkusTransaction.requiringNew().call(() ->
            channelService.findByName(CHANNEL_NAME)
                .map(c -> c.id)
                .orElseGet(() -> channelService.create(
                    new ChannelCreateRequest(
                        CHANNEL_NAME,
                        "Plugin activation commitment dispatch",
                        ChannelSemantic.APPEND,
                        null, null, null, null, null,
                        Set.of(MessageType.COMMAND, MessageType.DONE, MessageType.DECLINE),
                        null, null, null, null, null
                    )
                ).id)
        );
        log.infof("[DISPATCH-BROKER] Channel ready: %s", channelId);
    }

    void onGameStarted(@Observes GameStarted event) {
        // Clear so first tick re-establishes baseline for the new game.
        // lastDispatchedId is NOT reset — it is a monotonic DB cursor.
        priorActivation.clear();
        log.debugf("[DISPATCH-BROKER] State cleared for new game");
    }

    public UUID channelId()        { return channelId; }
    public long lastDispatchedId() { return lastDispatchedId; }

    /**
     * Evaluates each registered plugin's activation against caseData and emits
     * COMMAND+DONE or COMMAND+DECLINE for any that changed since the last tick.
     *
     * <p>Called from GameTickExecutor before createAndSolve(). All dispatches
     * share one transaction; collect-then-apply ensures priorActivation and
     * lastDispatchedId only advance after the transaction commits.
     */
    @Transactional
    public void recordTick(Map<String, Object> caseData) {
        var evalCtx    = new MapCaseContext(caseData);
        var toRemove   = new HashSet<String>();
        var toUpdate   = new LinkedHashMap<String, Boolean>();
        Long lastReplyId = null;

        for (var td : registry.getForCaseType("starcraft-game")) {
            if (!(td instanceof io.quarkmind.agent.TaskDefinition qmTd)) continue;

            boolean inScope = qmTd.requires().stream().allMatch(caseData::containsKey);
            if (!inScope) {
                toRemove.add(qmTd.getId());
                continue;
            }

            boolean nowActive = qmTd.activateIf().test(evalCtx);
            Boolean wasActive = priorActivation.get(qmTd.getId());

            if (wasActive == null || wasActive != nowActive) {
                lastReplyId = sendCommitmentSignal(qmTd.getId(), nowActive);
                toUpdate.put(qmTd.getId(), nowActive);
                log.debugf("[DISPATCH-BROKER] %s → %s", qmTd.getId(),
                    nowActive ? "DONE" : "DECLINE");
            }
        }

        // Apply in-memory state only after all dispatches succeeded.
        // If sendCommitmentSignal() throws → transaction rolls back → these lines
        // never execute → priorActivation unchanged → next tick re-detects and re-emits.
        toRemove.forEach(priorActivation::remove);
        toUpdate.forEach(priorActivation::put);
        if (lastReplyId != null) lastDispatchedId = lastReplyId;
    }

    private Long sendCommitmentSignal(String pluginId, boolean activating) {
        String correlationId = UUID.randomUUID().toString();

        DispatchResult commandResult = messageService.dispatch(
            MessageDispatch.builder()
                .channelId(channelId)
                .sender("agent.orchestrator")
                .type(MessageType.COMMAND)
                .correlationId(correlationId)
                .content(pluginId)
                .target("plugin:" + pluginId)
                .actorType(ActorType.SYSTEM)
                .build()
        );

        DispatchResult replyResult = messageService.dispatch(
            MessageDispatch.builder()
                .channelId(channelId)
                .sender("plugin:" + pluginId)
                .type(activating ? MessageType.DONE : MessageType.DECLINE)
                .correlationId(correlationId)
                .inReplyTo(commandResult.messageId())
                .actorType(ActorType.SYSTEM)
                .build()
        );

        return replyResult.messageId();
    }
}
