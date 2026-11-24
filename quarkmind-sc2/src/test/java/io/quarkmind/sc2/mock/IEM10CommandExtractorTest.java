package io.quarkmind.sc2.mock;

import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IEM10CommandExtractorTest {

    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");

    private static List<IEM10JsonSimulatedGame> games;
    private static IEM10JsonSimulatedGame firstGame;

    @BeforeAll
    static void loadGames() throws IOException {
        games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        firstGame = games.get(0);
    }

    @Test
    void extractReturnsNonEmptyForFirstGame() {
        List<TimedIntent> intents = IEM10CommandExtractor.extract(firstGame);
        assertThat(intents).isNotEmpty();
    }

    @Test
    void extractedIntentsAreAllTrainIntent() {
        List<TimedIntent> intents = IEM10CommandExtractor.extract(firstGame);
        assertThat(intents).allMatch(ti -> ti.intent() instanceof TrainIntent);
    }

    @Test
    void extractedIntentsHavePositiveLoops() {
        List<TimedIntent> intents = IEM10CommandExtractor.extract(firstGame);
        assertThat(intents).allMatch(ti -> ti.loop() > 0);
    }

    @Test
    void extractedIntentsAreOrderedByLoop() {
        List<TimedIntent> intents = IEM10CommandExtractor.extract(firstGame);
        for (int i = 1; i < intents.size(); i++) {
            assertThat(intents.get(i).loop()).isGreaterThanOrEqualTo(intents.get(i - 1).loop());
        }
    }

    @Test
    void probeIntentsHaveNexusBuildingTag() {
        List<TimedIntent> intents = IEM10CommandExtractor.extract(firstGame);
        List<TimedIntent> probeTrains = intents.stream()
            .filter(ti -> ((TrainIntent) ti.intent()).unitType() == UnitType.PROBE)
            .toList();
        assertThat(probeTrains).isNotEmpty();
        assertThat(probeTrains).allMatch(
            ti -> ((TrainIntent) ti.intent()).buildingTag().startsWith("j-"),
            "All Probe trains should carry a 'j-' building tag");
    }

    @Test
    void allGamesProduceNonTrivialProbeCount() {
        List<IEM10JsonSimulatedGame> protossGames = games.stream()
            .filter(IEM10JsonSimulatedGame::hasProtossPlayer)
            .toList();
        assertThat(protossGames).as("Dataset should contain Protoss games").isNotEmpty();
        for (IEM10JsonSimulatedGame game : protossGames) {
            List<TimedIntent> intents = IEM10CommandExtractor.extract(game);
            long probes = intents.stream()
                .filter(ti -> ((TrainIntent) ti.intent()).unitType() == UnitType.PROBE)
                .count();
            assertThat(probes)
                .as("Game %s should have at least 10 Probe trains", game.replayName())
                .isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void extractWithExplicitUserIdReturnsZergIntentsInPvZGame() {
        IEM10JsonSimulatedGame pvzGame = games.stream()
            .filter(g -> g.matchup().equals("PvZ"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No PvZ game found"));

        List<TimedIntent> zergIntents = List.of();
        for (int uid = 0; uid <= 7; uid++) {
            List<TimedIntent> candidate = IEM10CommandExtractor.extract(pvzGame, uid);
            if (uid == pvzGame.watchedUserId()) continue;
            boolean hasZergUnits = candidate.stream().anyMatch(ti -> {
                UnitType ut = ((TrainIntent) ti.intent()).unitType();
                return ut == UnitType.DRONE || ut == UnitType.ZERGLING || ut == UnitType.ROACH;
            });
            if (hasZergUnits) {
                zergIntents = candidate;
                break;
            }
        }
        assertThat(zergIntents)
            .as("Should find Zerg training intents in PvZ game")
            .isNotEmpty();
    }
}
