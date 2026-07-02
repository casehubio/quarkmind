package io.quarkmind.agent;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

class PluginDispatchBrokerTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();

    /** Stub plugin implementing the quarkmind TaskDefinition interface. */
    static class StubPlugin implements TaskDefinition {
        private final String id;
        private final Set<String> requiresKeys;
        private boolean activateResult;

        StubPlugin(String id, Set<String> requiresKeys, boolean activateResult) {
            this.id = id;
            this.requiresKeys = requiresKeys;
            this.activateResult = activateResult;
        }

        void setActivateResult(boolean v) { this.activateResult = v; }

        @Override public String getId()   { return id; }
        @Override public String getName() { return id; }
        @Override public Set<String> requires() { return requiresKeys; }
        @Override public Predicate<io.casehub.api.context.CaseContext> activateIf() {
            return ctx -> activateResult;
        }
        @Override public void execute(io.casehub.api.context.CaseContext ctx) {}
        @Override public Set<String> produces() { return Set.of(); }
    }

    private final List<MessageDispatch>  captured  = new ArrayList<>();
    private final AtomicLong             idCounter = new AtomicLong(1L);
    private boolean                      throwOnNextDispatch = false;

    private final MessageService stubMessageService = new MessageService() {
        @Override
        public DispatchResult dispatch(MessageDispatch d) {
            if (throwOnNextDispatch) {
                throwOnNextDispatch = false;
                throw new RuntimeException("simulated DB error");
            }
            captured.add(d);
            long id = idCounter.getAndIncrement();
            return new DispatchResult(id, d.channelId(), d.sender(), d.type(),
                d.correlationId(), d.inReplyTo(), null, d.target(),
                null, null, null, 0, null);
        }
    };

    private PluginDispatchBroker brokerWith(TaskDefinition... plugins) {
        return new PluginDispatchBroker(List.of(plugins), stubMessageService, CHANNEL_ID);
    }

    @BeforeEach
    void setUp() {
        captured.clear();
        throwOnNextDispatch = false;
    }

    @Test
    void firstTickActivatingPluginEmitsCommandPlusDone() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).type()).isEqualTo(MessageType.COMMAND);
        assertThat(captured.get(0).target()).isEqualTo("plugin:scouting");
        assertThat(captured.get(1).type()).isEqualTo(MessageType.DONE);
        assertThat(captured.get(1).sender()).isEqualTo("plugin:scouting");
        assertThat(captured.get(1).inReplyTo()).isEqualTo(1L);
    }

    @Test
    void firstTickDecliningPluginEmitsCommandPlusDecline() {
        var plugin = new StubPlugin("strategy.early", Set.of("READY"), false);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).type()).isEqualTo(MessageType.COMMAND);
        assertThat(captured.get(1).type()).isEqualTo(MessageType.DECLINE);
        assertThat(captured.get(1).sender()).isEqualTo("plugin:strategy.early");
    }

    @Test
    void repeatedTickSameStateSendsNoSignal() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));
        captured.clear();

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).isEmpty();
    }

    @Test
    void stateChangeActiveToInactiveEmitsCommandPlusDecline() {
        var plugin = new StubPlugin("tactics", Set.of("READY"), true);
        var broker = brokerWith(plugin);
        broker.recordTick(Map.of("READY", true));
        captured.clear();

        plugin.setActivateResult(false);
        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).type()).isEqualTo(MessageType.COMMAND);
        assertThat(captured.get(1).type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void stateChangeInactiveToActiveEmitsCommandPlusDone() {
        var plugin = new StubPlugin("tactics", Set.of("READY"), false);
        var broker = brokerWith(plugin);
        broker.recordTick(Map.of("READY", true));
        captured.clear();

        plugin.setActivateResult(true);
        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).type()).isEqualTo(MessageType.DONE);
    }

    @Test
    void outOfScopePluginSendsNoSignal() {
        var plugin = new StubPlugin("tactics", Set.of("READY", "STRATEGY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).isEmpty();
    }

    @Test
    void scopeReentryAfterAbsenceTreatedAsFirstSeen() {
        var plugin = new StubPlugin("tactics", Set.of("READY", "STRATEGY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));
        broker.recordTick(Map.of("READY", true, "STRATEGY", "ATTACK"));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).type()).isEqualTo(MessageType.DONE);
    }

    @Test
    void gameStartedClearsState_nextTickReestablishesBaseline() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));
        long idAfterFirstGame = broker.lastDispatchedId();
        captured.clear();

        broker.onGameStarted(new GameStarted());

        // lastDispatchedId must NOT reset on GameStarted — it is a monotonic DB cursor
        assertThat(broker.lastDispatchedId()).isEqualTo(idAfterFirstGame);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
    }

    @Test
    void lastDispatchedIdAdvancesAfterSuccessfulDispatch() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        assertThat(broker.lastDispatchedId()).isEqualTo(0L);

        broker.recordTick(Map.of("READY", true));

        assertThat(broker.lastDispatchedId()).isEqualTo(2L);
    }

    @Test
    void rollbackSimulation_priorActivationUnchangedOnDispatchFailure() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        throwOnNextDispatch = true;

        assertThatThrownBy(() -> broker.recordTick(Map.of("READY", true)))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("simulated DB error");

        assertThat(captured).isEmpty();
        broker.recordTick(Map.of("READY", true));
        assertThat(captured).hasSize(2);
    }

    @Test
    void correlationIdPairsCommandWithReply() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).correlationId())
            .isEqualTo(captured.get(1).correlationId())
            .isNotNull();
    }

    @Test
    void commandTargetContainsColonToBypassObligorTrustCheck() {
        var plugin = new StubPlugin("scouting", Set.of("READY"), true);
        var broker = brokerWith(plugin);

        broker.recordTick(Map.of("READY", true));

        assertThat(captured.get(0).target()).contains(":");
        assertThat(captured.get(0).target()).isEqualTo("plugin:scouting");
    }
}
