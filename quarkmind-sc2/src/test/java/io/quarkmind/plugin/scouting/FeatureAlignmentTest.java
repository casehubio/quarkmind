package io.quarkmind.plugin.scouting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Regression gate: asserts Java feature extraction matches the Python reference
 * fixture for the same IEM10 replay at the same game time.
 */
class FeatureAlignmentTest {

    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");
    private static final float TOLERANCE = 1e-3f;

    @Test
    void playerFeatures_matchPythonReference() throws Exception {
        JsonNode fixture = loadFixture();
        String replayName = fixture.get("replay_name").asText();
        int targetSecond = fixture.get("second").asInt();
        float[] pyPlayerFeatures = jsonArrayToFloat(fixture.get("player_features"));
        float[] pyOpponentFeatures = jsonArrayToFloat(fixture.get("opponent_features"));

        assertThat(pyPlayerFeatures.length).isEqualTo(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);
        assertThat(pyOpponentFeatures.length).isEqualTo(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);

        IEM10JsonSimulatedGame game = findGame(replayName);
        assertThat(game).as("Game '%s' not found in IEM10 ZIP", replayName).isNotNull();

        GameState state = tickToSecond(game, targetSecond);
        assertThat(state.mapInfo()).as("MapInfo must be populated for spatial features").isNotNull();

        float[] pyPlayerStart = jsonArrayToFloat(fixture.get("player_start"));
        float[] pyEnemyStart = jsonArrayToFloat(fixture.get("enemy_start"));
        assertThat(state.mapInfo().playerStart().x()).isCloseTo(pyPlayerStart[0], within(0.1f));
        assertThat(state.mapInfo().playerStart().y()).isCloseTo(pyPlayerStart[1], within(0.1f));
        assertThat(state.mapInfo().enemyStart().x()).isCloseTo(pyEnemyStart[0], within(0.1f));
        assertThat(state.mapInfo().enemyStart().y()).isCloseTo(pyEnemyStart[1], within(0.1f));

        WindowSnapshot snapshot = DroolsScoutingTask.buildSnapshot(state);
        float[] javaPlayer = snapshot.playerFeatures();
        float[] javaOpponent = snapshot.opponentFeatures();

        StringBuilder report = new StringBuilder();
        report.append("=== Feature Alignment Report ===\n");
        int playerMismatches = compareFeatures(report, "PLAYER", javaPlayer, pyPlayerFeatures);
        int opponentMismatches = compareFeatures(report, "OPPONENT", javaOpponent, pyOpponentFeatures);

        if (playerMismatches > 0 || opponentMismatches > 0) {
            System.out.println(report);
        }

        assertThat(playerMismatches)
                .as("Player feature mismatches (see report above)")
                .isZero();
    }

    @Test
    void supplyCosts_matchJsonArtifact() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getResourceAsStream("/classifier/supply_costs.json")) {
            assertThat(is).as("supply_costs.json must exist on classpath").isNotNull();
            Map<String, Integer> costs = mapper.readValue(is,
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, Integer.class));
            for (UnitType type : UnitType.values()) {
                assertThat(costs.get(type.name()))
                        .as("Supply cost for %s", type.name())
                        .isEqualTo(SC2Data.supplyCost(type));
            }
        }
    }

    private static int compareFeatures(StringBuilder report, String label,
                                        float[] java, float[] python) {
        int mismatches = 0;
        report.append(String.format("\n--- %s (Java vs Python) ---%n", label));
        for (int i = 0; i < java.length; i++) {
            float diff = Math.abs(java[i] - python[i]);
            if (diff > TOLERANCE) {
                String region = featureRegion(i);
                report.append(String.format("  [%3d] %-20s java=%.6f  python=%.6f  diff=%.6f%n",
                        i, region, java[i], python[i], diff));
                mismatches++;
            }
        }
        if (mismatches == 0) {
            report.append("  All features within tolerance.\n");
        }
        return mismatches;
    }

    private static String featureRegion(int idx) {
        if (idx < FeatureIndexMaps.N_BUILDINGS) return "building[" + idx + "]";
        int unitStart = FeatureIndexMaps.N_BUILDINGS;
        if (idx < unitStart + FeatureIndexMaps.N_UNITS) return "unit[" + (idx - unitStart) + "]";
        int statsStart = unitStart + FeatureIndexMaps.N_UNITS;
        if (idx < statsStart + FeatureIndexMaps.N_STATS) return "stat[" + (idx - statsStart) + "]";
        int upgradeStart = statsStart + FeatureIndexMaps.N_STATS;
        if (idx < upgradeStart + FeatureIndexMaps.N_UPGRADES) return "upgrade[" + (idx - upgradeStart) + "]";
        if (idx < FeatureIndexMaps.RATIO_OFFSET) return "spatial[" + (idx - FeatureIndexMaps.SPATIAL_OFFSET) + "]";
        return "ratio[" + (idx - FeatureIndexMaps.RATIO_OFFSET) + "]";
    }

    private static GameState tickToSecond(IEM10JsonSimulatedGame game, int targetSecond) {
        long targetLoop = (long) (targetSecond * SC2Data.GAME_LOOPS_PER_SECOND);
        int ticksNeeded = (int) Math.ceil((double) targetLoop / SC2Data.LOOPS_PER_TICK) + 1;
        for (int i = 0; i < ticksNeeded; i++) {
            game.tick();
        }
        return game.snapshot();
    }

    private static IEM10JsonSimulatedGame findGame(String replayName) throws IOException {
        List<IEM10JsonSimulatedGame> games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        return games.stream()
                .filter(g -> g.replayName().equals(replayName))
                .findFirst()
                .orElse(null);
    }

    private static JsonNode loadFixture() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = FeatureAlignmentTest.class.getResourceAsStream(
                "/classifier/alignment_fixture.json")) {
            assertThat(is).as("alignment_fixture.json must exist on classpath").isNotNull();
            return mapper.readTree(is);
        }
    }

    private static float[] jsonArrayToFloat(JsonNode arrayNode) {
        float[] result = new float[arrayNode.size()];
        for (int i = 0; i < arrayNode.size(); i++) {
            result[i] = (float) arrayNode.get(i).asDouble();
        }
        return result;
    }
}
