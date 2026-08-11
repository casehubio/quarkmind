package io.quarkmind.agent;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class GameStateTranslator {

    private final AtomicReference<String> opponentId = new AtomicReference<>("unknown");

    void onGameStarted(@Observes GameStarted event) {
        opponentId.set(computeOpponentId(event.opponentRace(), event.opponentType(),
                                         event.opponentDifficulty(), event.opponentPlayerId()));
    }

    static String computeOpponentId(String race, String playerType, String difficulty, String playerId) {
        if ("UNKNOWN".equals(race) || "UNKNOWN".equals(playerType)) {
            return "unknown";
        }
        if ("COMPUTER".equals(playerType)) {
            return sha256(race + ":" + difficulty);
        }
        if (playerId != null && !playerId.isEmpty()) {
            return sha256(playerId);
        }
        return sha256(race + ":PARTICIPANT");
    }

    public Map<String, Object> toMap(GameState state) {
        Map<String, Object> data = new HashMap<>();
        data.put(QuarkMindCaseFile.GAME_STATE, state);
        data.put(QuarkMindCaseFile.MINERALS, state.minerals());
        data.put(QuarkMindCaseFile.VESPENE, state.vespene());
        data.put(QuarkMindCaseFile.SUPPLY_CAP, state.supply());
        data.put(QuarkMindCaseFile.SUPPLY_USED, state.supplyUsed());
        data.put(QuarkMindCaseFile.GAME_FRAME, state.gameFrame());
        data.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        data.put(QuarkMindCaseFile.OPPONENT_ID, opponentId.get());

        List<Unit> workers = state.myUnits().stream()
                                  .filter(u -> u.type().isWorker()).toList();
        List<Unit> army = state.myUnits().stream()
                               .filter(u -> !u.type().isWorker()).toList();

        data.put(QuarkMindCaseFile.WORKERS, workers);
        data.put(QuarkMindCaseFile.ARMY, army);
        data.put(QuarkMindCaseFile.MY_BUILDINGS, state.myBuildings());
        data.put(QuarkMindCaseFile.GEYSERS, state.geysers());
        data.put(QuarkMindCaseFile.ENEMY_UNITS, state.enemyUnits());
        data.put(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(state.minerals(), state.vespene()));
        return data;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[]        hash   = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
