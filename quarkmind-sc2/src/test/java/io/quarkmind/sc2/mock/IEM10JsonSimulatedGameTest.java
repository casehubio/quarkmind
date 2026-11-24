package io.quarkmind.sc2.mock;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests IEM10JsonSimulatedGame using replays/2016_IEM_10_Taipei.zip.
 *
 * First game in the inner ZIP: ByuN (Terr, playerID=1) vs Lilbow (Prot, playerID=2).
 * → watchedPlayerId=2 (Lilbow/Protoss), matchup="PvT".
 */
class IEM10JsonSimulatedGameTest {

    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");

    private static List<IEM10JsonSimulatedGame> games;

    @BeforeAll
    static void loadGames() throws IOException {
        games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
    }

    @BeforeEach
    void resetGames() {
        games.forEach(IEM10JsonSimulatedGame::reset);
    }

    // ---- enumerate() factory ----

    @Test
    void enumerateReturns30Games() {
        assertThat(games).hasSize(30);
    }

    @Test
    void enumeratedGamesHaveReplayNames() {
        assertThat(games).allMatch(g -> g.replayName().endsWith(".SC2Replay.json"));
    }

    @Test
    void enumeratedGamesHaveValidMatchups() {
        assertThat(games).allMatch(g ->
            g.matchup().equals("PvP") || g.matchup().equals("PvT") || g.matchup().equals("PvZ"));
    }

    @Test
    void datasetContainsPvTAndPvZAndPvP() {
        assertThat(games.stream().map(IEM10JsonSimulatedGame::matchup))
            .contains("PvT", "PvZ", "PvP");
    }

    // ---- matchup detection ----

    @Test
    void firstGameMatchupIsPvT() {
        assertThat(games.get(0).matchup()).isEqualTo("PvT");
    }

    // ---- initial state ----

    @Test
    void initialStateHasNexus() {
        GameState state = games.get(0).snapshot();
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS))
            .as("Nexus present at game start").isTrue();
    }

    @Test
    void initialStateHas12Probes() {
        GameState state = games.get(0).snapshot();
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count())
            .as("12 probes at game start").isEqualTo(12);
    }

    @Test
    void initialStateHasMineralPatches() {
        GameState state = games.get(0).snapshot();
        assertThat(state.mineralPatches())
                .as("Mineral patches present at game start (neutral UnitBorn at loop 0)")
                .isNotEmpty();
    }

    @Test
    void initialStateHasGeysers() {
        GameState state = games.get(0).snapshot();
        assertThat(state.geysers())
                .as("Geysers present at game start (neutral UnitBorn at loop 0)")
                .isNotEmpty();
    }

    @Test
    void mineralPatchesHavePositiveRemaining() {
        assertThat(games.get(0).snapshot().mineralPatches())
                .allMatch(r -> r.remaining() > 0, "all mineral patches have positive remaining");
    }

    @Test
    void geysersHavePositiveRemaining() {
        assertThat(games.get(0).snapshot().geysers())
                .allMatch(r -> r.remaining() > 0, "all geysers have positive remaining");
    }


    @Test
    void initialGameFrameIsZero() {
        assertThat(games.get(0).snapshot().gameFrame()).isEqualTo(0L);
    }

    // ---- tick() progression ----

    @Test
    void tickAdvancesGameFrameByOne() {
        IEM10JsonSimulatedGame game = games.get(0);
        game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(1L);
        game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(2L);
    }

    @Test
    void mineralsPopulatedAfterFewTicks() {
        IEM10JsonSimulatedGame game = games.get(0);
        for (int i = 0; i < 5; i++) game.tick();
        assertThat(game.snapshot().minerals())
            .as("Minerals from PlayerStats after 5 ticks").isGreaterThan(0);
    }

    @Test
    void supplyPopulatedAfterFewTicks() {
        IEM10JsonSimulatedGame game = games.get(0);
        for (int i = 0; i < 5; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.supply()).as("Supply cap > 0").isGreaterThan(0);
        assertThat(state.supplyUsed()).as("Supply used > 0").isGreaterThan(0);
    }

    @Test
    void unitCountGrowsOver3Minutes() {
        IEM10JsonSimulatedGame game = games.get(0);
        int initialUnits = game.snapshot().myUnits().size();
        for (int i = 0; i < 183; i++) game.tick(); // 3 min
        assertThat(game.snapshot().myUnits().size())
            .as("More units after 3 min").isGreaterThan(initialUnits);
    }

    // ---- isComplete() ----

    @Test
    void isNotCompleteAtStart() {
        assertThat(games.get(0).isComplete()).isFalse();
    }

    @Test
    void isCompleteAfterAllEvents() {
        IEM10JsonSimulatedGame game = games.get(0);
        while (!game.isComplete()) game.tick();
        assertThat(game.isComplete()).isTrue();
    }

    // ---- reset() ----

    @Test
    void resetRestoresInitialState() {
        IEM10JsonSimulatedGame game = games.get(0);
        for (int i = 0; i < 100; i++) game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(100L);

        game.reset();
        GameState state = game.snapshot();
        assertThat(state.gameFrame()).isEqualTo(0L);
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS)).isTrue();
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
    }

    // ---- applyIntent() is a no-op ----

    @Test
    void applyIntentIsNoOp() {
        IEM10JsonSimulatedGame game = games.get(0);
        int buildingsBeforeIntent = game.snapshot().myBuildings().size();
        String probeTag = game.snapshot().myUnits().get(0).tag();
        game.applyIntent(io.quarkmind.sc2.intent.BuildIntent.of(
            probeTag, BuildingType.PYLON, new Point2d(20, 20)));
        game.tick();
        assertThat(game.snapshot().myBuildings().size())
            .as("applyIntent is a no-op").isEqualTo(buildingsBeforeIntent);
    }

    // ---- enemy unit tracking ----

    @Test
    void terranEnemyUnitsTrackedInPvTGame() {
        IEM10JsonSimulatedGame pvtGame = games.stream()
            .filter(g -> g.matchup().equals("PvT"))
            .findFirst().orElseThrow(() -> new AssertionError("No PvT game found"));
        for (int i = 0; i < 183; i++) pvtGame.tick();
        long marines = pvtGame.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE).count();
        assertThat(marines).as("Marines visible in PvT game at 3 min").isGreaterThan(0);
    }

    @Test
    void zergEnemyUnitsTrackedInPvZGame() {
        IEM10JsonSimulatedGame pvzGame = games.stream()
            .filter(g -> g.matchup().equals("PvZ"))
            .findFirst().orElseThrow(() -> new AssertionError("No PvZ game found"));
        for (int i = 0; i < 183; i++) pvzGame.tick();
        long zergUnits = pvzGame.snapshot().enemyUnits().stream()
            .filter(u -> u.type() != UnitType.UNKNOWN).count();
        assertThat(zergUnits).as("Zerg enemy units visible in PvZ game at 3 min").isGreaterThan(0);
    }
}
