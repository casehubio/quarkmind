package io.quarkmind.sc2.real;

import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStopped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiGameControllerTest {

    private MultiGameController controller;
    private int startGameCalls;

    @BeforeEach
    void setUp() {
        controller = new MultiGameController();
        startGameCalls = 0;
        controller.orchestrator = new StubOrchestrator(() -> startGameCalls++);
        controller.delayBetweenGamesMs = 50;
    }

    @Test
    void singleGame_doesNotRestartAfterCompletion() throws Exception {
        controller.gameCount = 1;
        controller.onGameStopped(new GameStopped(GameResult.WIN));
        Thread.sleep(200);

        assertThat(startGameCalls).isZero();
        assertThat(controller.gamesCompleted()).isEqualTo(1);
        assertThat(controller.results()).containsExactly(GameResult.WIN);
    }

    @Test
    void multiGame_restartsUntilCountReached() throws Exception {
        controller.gameCount = 3;

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        Thread.sleep(200);
        assertThat(startGameCalls).isEqualTo(1);
        assertThat(controller.gamesCompleted()).isEqualTo(1);

        controller.onGameStopped(new GameStopped(GameResult.LOSS));
        Thread.sleep(200);
        assertThat(startGameCalls).isEqualTo(2);
        assertThat(controller.gamesCompleted()).isEqualTo(2);

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        Thread.sleep(200);
        assertThat(startGameCalls).isEqualTo(2);
        assertThat(controller.gamesCompleted()).isEqualTo(3);
    }

    @Test
    void results_tracksAllOutcomesInOrder() {
        controller.gameCount = 4;

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        controller.onGameStopped(new GameStopped(GameResult.LOSS));
        controller.onGameStopped(new GameStopped(GameResult.TIE));
        controller.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(controller.results()).containsExactly(
            GameResult.WIN, GameResult.LOSS, GameResult.TIE, GameResult.WIN);
    }

    @Test
    void summary_countsWinsLossesAndOther() {
        controller.gameCount = 5;

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        controller.onGameStopped(new GameStopped(GameResult.WIN));
        controller.onGameStopped(new GameStopped(GameResult.LOSS));
        controller.onGameStopped(new GameStopped(GameResult.WIN));
        controller.onGameStopped(new GameStopped(GameResult.UNKNOWN));

        assertThat(controller.summary()).isEqualTo("5 games: 3W 1L 1 other");
    }

    @Test
    void isAllGamesComplete_falseUntilDone() {
        controller.gameCount = 2;

        assertThat(controller.isAllGamesComplete()).isFalse();

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        assertThat(controller.isAllGamesComplete()).isFalse();

        controller.onGameStopped(new GameStopped(GameResult.WIN));
        assertThat(controller.isAllGamesComplete()).isTrue();
    }

    static class StubOrchestrator extends io.quarkmind.agent.AgentOrchestrator {
        private final Runnable onStart;

        StubOrchestrator(Runnable onStart) {
            this.onStart = onStart;
        }

        @Override
        public void startGame() {
            onStart.run();
        }
    }
}
