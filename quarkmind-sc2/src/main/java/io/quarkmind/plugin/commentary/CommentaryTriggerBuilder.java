package io.quarkmind.plugin.commentary;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds commentary trigger signals from L2 game moments (Pattern A).
 *
 * <p>CDI bean (not static) — cooldown requires instance state. Reacts to ALL
 * {@link io.quarkmind.plugin.summarisation.GameMomentType} values (unlike
 * {@link io.quarkmind.agent.AdvisoryTriggerBuilder} which is selective).
 *
 * <p>Maps all moments from the current tick to a single {@link QuarkMindCaseFile#COMMENTARY_TRIGGER}
 * key with cooldown enforcement (110 frames ~5s at 22.4fps). Batches all moment types
 * into one trigger payload.
 *
 * <p>Refs #181 (Task 5)
 */
@ApplicationScoped
public class CommentaryTriggerBuilder {

    private static final int COOLDOWN_FRAMES = 110; // ~5s at 22.4fps

    private long lastFiredFrame = -COOLDOWN_FRAMES; // allow first fire immediately

    /**
     * Build commentary trigger map from latest moments.
     *
     * @param ctx       CaseContext containing {@link QuarkMindCaseFile#MOMENTS_LATEST}
     * @param gameFrame current game frame (monotonic value for trigger differentiation)
     * @return map containing {@link QuarkMindCaseFile#COMMENTARY_TRIGGER} → payload,
     *         or empty map if no moments present or cooldown has not elapsed
     */
    public Map<String, Object> build(CaseContext ctx, long gameFrame) {
        List<GameMoment> moments = ctx.getList(QuarkMindCaseFile.MOMENTS_LATEST, GameMoment.class);
        if (moments == null || moments.isEmpty()) {
            return Map.of();
        }

        // Cooldown check
        if (gameFrame - lastFiredFrame < COOLDOWN_FRAMES) {
            return Map.of();
        }

        lastFiredFrame = gameFrame;

        List<String> typeNames = moments.stream()
            .map(m -> m.type().name())
            .toList();

        // Extract game state for Worker context (handle null values gracefully)
        int minerals = getIntOrZero(ctx, QuarkMindCaseFile.MINERALS);
        int supplyUsed = getIntOrZero(ctx, QuarkMindCaseFile.SUPPLY_USED);
        int supplyCap = getIntOrZero(ctx, QuarkMindCaseFile.SUPPLY_CAP);
        int army = getIntOrZero(ctx, QuarkMindCaseFile.ARMY);

        var trigger = new LinkedHashMap<String, Object>();
        trigger.put("gameFrame", gameFrame);
        trigger.put("momentTypes", typeNames);
        trigger.put("minerals", minerals);
        trigger.put("supplyUsed", supplyUsed);
        trigger.put("supplyCap", supplyCap);
        trigger.put("army", army);

        Map<String, Object> cbrContext = extractCbrContext(ctx);
        if (cbrContext != null) {
            trigger.put("cbrContext", cbrContext);
        }

        return Map.of(QuarkMindCaseFile.COMMENTARY_TRIGGER, trigger);
    }

    /**
     * Reset cooldown on game start.
     */
    void onGameStarted(@Observes GameStarted event) {
        lastFiredFrame = -COOLDOWN_FRAMES;
    }

    private static int getIntOrZero(CaseContext ctx, String key) {
        Integer value = ctx.getAs(key, Integer.class);
        return value != null ? value : 0;
    }

    private static Map<String, Object> extractCbrContext(CaseContext ctx) {
        Boolean influenced   = ctx.getAs(QuarkMindCaseFile.CBR_INFLUENCED_SELECTION, Boolean.class);
        Integer similarCount = ctx.getAs(QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT, Integer.class);
        String  prediction   = ctx.getAs(QuarkMindCaseFile.TEMPORAL_PREDICTION, String.class);
        if (influenced == null && similarCount == null && prediction == null) {
            return null;
        }
        var cbr = new LinkedHashMap<String, Object>();
        if (influenced != null) {cbr.put("influenced", influenced);}
        if (similarCount != null) {cbr.put("similarCount", similarCount);}
        if (prediction != null) {cbr.put("prediction", prediction);}
        return cbr;
    }

}
