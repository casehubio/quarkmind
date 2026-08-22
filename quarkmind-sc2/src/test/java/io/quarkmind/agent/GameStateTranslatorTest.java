package io.quarkmind.agent;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GameStateTranslatorTest {

    GameStateTranslator translator = new GameStateTranslator();

    {
        translator.onGameStarted(new GameStarted("PROTOSS", "COMPUTER", "VeryEasy", null));
    }

    @Test
    void translatesResourcesCorrectly() {
        var                 state = new GameState(150, 75, 23, 14, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 42L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        Map<String, Object> map   = translator.toMap(state);
        assertThat(map.get(QuarkMindCaseFile.MINERALS)).isEqualTo(150);
        assertThat(map.get(QuarkMindCaseFile.VESPENE)).isEqualTo(75);
        assertThat(map.get(QuarkMindCaseFile.SUPPLY_CAP)).isEqualTo(23);
        assertThat(map.get(QuarkMindCaseFile.SUPPLY_USED)).isEqualTo(14);
        assertThat(map.get(QuarkMindCaseFile.GAME_FRAME)).isEqualTo(42L);
        assertThat(map.get(QuarkMindCaseFile.READY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void separatesWorkersFromArmy() {
        var                 probe  = new Unit("p1", UnitType.PROBE, new Point2d(0, 0), 45, 45, 20, 20, 0, 0);
        var                 zealot = new Unit("z1", UnitType.ZEALOT, new Point2d(1, 1), 100, 100, 50, 50, 0, 0);
        var                 state  = new GameState(50, 0, 15, 3, List.of(probe, zealot), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        Map<String, Object> map    = translator.toMap(state);
        assertThat((List<?>) map.get(QuarkMindCaseFile.WORKERS)).hasSize(1);
        assertThat((List<?>) map.get(QuarkMindCaseFile.ARMY)).hasSize(1);
    }

    @Test
    void classifiesAllRaceWorkersCorrectly() {
        var probe    = new Unit("p1", UnitType.PROBE, new Point2d(0, 0), 20, 20, 20, 20, 0, 0);
        var scv      = new Unit("s1", UnitType.SCV, new Point2d(1, 1), 45, 45, 45, 45, 0, 0);
        var drone    = new Unit("d1", UnitType.DRONE, new Point2d(2, 2), 40, 40, 40, 40, 0, 0);
        var marine   = new Unit("m1", UnitType.MARINE, new Point2d(3, 3), 45, 45, 55, 55, 0, 0);
        var zealot   = new Unit("z1", UnitType.ZEALOT, new Point2d(4, 4), 100, 100, 50, 50, 0, 0);
        var zergling = new Unit("zl1", UnitType.ZERGLING, new Point2d(5, 5), 35, 35, 35, 35, 0, 0);

        var state = new GameState(100, 50, 30, 10, List.of(probe, scv, drone, marine, zealot, zergling), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 100L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        Map<String, Object> map = translator.toMap(state);

        @SuppressWarnings("unchecked")
        List<Unit> workers = (List<Unit>) map.get(QuarkMindCaseFile.WORKERS);
        @SuppressWarnings("unchecked")
        List<Unit> army = (List<Unit>) map.get(QuarkMindCaseFile.ARMY);

        assertThat(workers).hasSize(3).extracting(Unit::type)
                           .containsExactlyInAnyOrder(UnitType.PROBE, UnitType.SCV, UnitType.DRONE);
        assertThat(army).hasSize(3).extracting(Unit::type)
                        .containsExactlyInAnyOrder(UnitType.MARINE, UnitType.ZEALOT, UnitType.ZERGLING);
    }

    @Test
    void includesOpponentId() {
        var                 state = new GameState(50, 0, 15, 3, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        Map<String, Object> map   = translator.toMap(state);
        assertThat(map.get(QuarkMindCaseFile.OPPONENT_ID)).isNotNull();
        assertThat((String) map.get(QuarkMindCaseFile.OPPONENT_ID)).hasSize(64);
    }

    @Test
    void toMap_defaultsToUnknownBeforeGameStarted() {
        var                 fresh = new GameStateTranslator();
        var                 state = new GameState(50, 0, 15, 3, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        Map<String, Object> map   = fresh.toMap(state);
        assertThat(map.get(QuarkMindCaseFile.OPPONENT_ID)).isEqualTo("unknown");
    }

    @Test
    void computeOpponentId_aiOpponent_hashesRaceAndDifficulty() {
        String id       = GameStateTranslator.computeOpponentId("ZERG", "COMPUTER", "VeryHard", null);
        String expected = GameStateTranslator.computeOpponentId("ZERG", "COMPUTER", "VeryHard", null);
        assertThat(id).isEqualTo(expected);
        assertThat(id).hasSize(64);
    }

    @Test
    void computeOpponentId_pvpOpponent_hashesPlayerId() {
        String id = GameStateTranslator.computeOpponentId("PROTOSS", "PARTICIPANT", null, "12345");
        assertThat(id).hasSize(64);
        String id2 = GameStateTranslator.computeOpponentId("PROTOSS", "PARTICIPANT", null, "67890");
        assertThat(id).isNotEqualTo(id2);
    }
}
