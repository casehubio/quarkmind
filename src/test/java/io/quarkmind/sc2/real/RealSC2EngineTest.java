package io.quarkmind.sc2.real;

import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.GameResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RealSC2Engine lifecycle contracts.
 * Instantiates directly — no CDI required.
 */
class RealSC2EngineTest {

    @Test
    void connectFallback_throwsIllegalStateException_haltingLifecycleChain() {
        // connectFallback() must throw — returning normally would let subsequent
        // joinGame() calls hit a null socket on an unconnected transport.
        var engine = new RealSC2Engine();
        assertThatThrownBy(engine::connectFallback)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("game cannot start");
    }

    @Test
    void lastOutcome_returnsUnknown_whenBotAgentIsNull() {
        var engine = new RealSC2Engine();
        assertThat(engine.lastOutcome()).isEqualTo(GameResult.UNKNOWN);
    }

    @Test
    void lastOutcome_reflectsBotAgentLastOutcome_afterGameEnd() {
        var botAgent = new SC2BotAgent();
        var engine   = new RealSC2Engine();
        engine.botAgent = botAgent;

        botAgent.onGameEnd(GameResult.WIN);

        assertThat(engine.lastOutcome()).isEqualTo(GameResult.WIN);
    }

    @Test
    void observe_firesFrameListeners_withGameState() {
        var botAgent = new SC2BotAgent();
        var engine   = new RealSC2Engine();
        engine.botAgent = botAgent;

        GameState state = new GameState(100, 50, 12, 0,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 42L);
        botAgent.latestGameState.set(state);

        List<GameState> captured = new ArrayList<>();
        engine.addFrameListener(captured::add);

        GameState observed = engine.observe();

        assertThat(observed).isSameAs(state);
        assertThat(captured).containsExactly(state);
    }

    @Test
    void observe_firesFrameListeners_withEmptyState_whenBotAgentReturnsNull() {
        var botAgent = new SC2BotAgent();
        var engine   = new RealSC2Engine();
        engine.botAgent = botAgent;

        List<GameState> captured = new ArrayList<>();
        engine.addFrameListener(captured::add);

        engine.observe();

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).gameFrame()).isEqualTo(0);
    }

    @Test
    void addFrameListener_supportsMultipleListeners() {
        var botAgent = new SC2BotAgent();
        var engine   = new RealSC2Engine();
        engine.botAgent = botAgent;

        GameState state = new GameState(100, 50, 12, 0,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 1L);
        botAgent.latestGameState.set(state);

        List<GameState> captured1 = new ArrayList<>();
        List<GameState> captured2 = new ArrayList<>();
        engine.addFrameListener(captured1::add);
        engine.addFrameListener(captured2::add);

        engine.observe();

        assertThat(captured1).containsExactly(state);
        assertThat(captured2).containsExactly(state);
    }

    @Test
    void lastOutcome_resetsToUnknown_onGameStart() {
        var botAgent = new SC2BotAgent();
        var engine   = new RealSC2Engine();
        engine.botAgent = botAgent;

        // Simulate previous game result
        botAgent.onGameEnd(GameResult.LOSS);
        assertThat(engine.lastOutcome()).isEqualTo(GameResult.LOSS);

        // New game starts — onGameStart resets lastOutcome.
        // Build minimal ResponseGameInfo with no StartRaw so terrain extraction is skipped.
        SC2APIProtocol.Sc2Api.ResponseGameInfo proto = SC2APIProtocol.Sc2Api.ResponseGameInfo.newBuilder()
            .setMapName("test-map")
            .addPlayerInfo(SC2APIProtocol.Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(1)
                .setType(SC2APIProtocol.Sc2Api.PlayerType.Participant)
                .setRaceRequested(SC2APIProtocol.Common.Race.Protoss)
                .build())
            .setOptions(SC2APIProtocol.Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
            .build();
        com.github.ocraft.s2client.protocol.response.ResponseGameInfo gameInfo =
            com.github.ocraft.s2client.protocol.response.ResponseGameInfo.from(
                SC2APIProtocol.Sc2Api.Response.newBuilder()
                    .setGameInfo(proto).setStatus(SC2APIProtocol.Sc2Api.Status.in_game).build());
        botAgent.onGameStart(gameInfo);
        assertThat(engine.lastOutcome()).isEqualTo(GameResult.UNKNOWN);
    }
}
