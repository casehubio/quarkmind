package io.quarkmind.plugin.coaching;

import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.casehub.api.context.CaseContext;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CoachingTriggerBuilder {

    private long lastFiredFrame = -1;
    private CoachingUrgencyTier lastFiredTier;

    public Map<String, Object> build(CaseContext ctx, long gameFrame) {
        List<GameMoment> moments = ctx.getList(QuarkMindCaseFile.MOMENTS_LATEST, GameMoment.class);
        if (moments == null || moments.isEmpty()) return Map.of();

        CoachingUrgencyTier highestTier = null;
        List<String> momentTypes = new ArrayList<>();

        for (GameMoment moment : moments) {
            CoachingUrgencyTier tier = mapMomentToTier(moment.type());
            if (tier == null) continue;
            momentTypes.add(moment.type().name());
            if (highestTier == null || tier.ordinal() < highestTier.ordinal()) {
                highestTier = tier;
            }
        }

        if (highestTier == null) return Map.of();
        if (!canFire(highestTier, gameFrame)) return Map.of();

        lastFiredFrame = gameFrame;
        lastFiredTier = highestTier;

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("gameFrame", gameFrame);
        trigger.put("urgencyTier", highestTier.name());
        trigger.put("momentTypes", momentTypes);

        return Map.of(QuarkMindCaseFile.COACHING_TRIGGER, trigger);
    }

    void onGameStarted(@Observes GameStarted event) {
        lastFiredFrame = -1;
        lastFiredTier = null;
    }

    private boolean canFire(CoachingUrgencyTier requestedTier, long gameFrame) {
        if (lastFiredFrame < 0) return true;
        if (requestedTier.ordinal() < lastFiredTier.ordinal()) return true;
        long elapsed = gameFrame - lastFiredFrame;
        return elapsed >= lastFiredTier.cooldownFrames();
    }

    static CoachingUrgencyTier mapMomentToTier(GameMomentType type) {
        return switch (type) {
            case NEXUS_UNDER_ATTACK, BATTLE_STARTED, BUILDING_LOST -> CoachingUrgencyTier.CRISIS;
            case TECH_TRANSITION_DETECTED, ARMY_SHIFT, POSTURE_CHANGE, FIRST_CONTACT -> CoachingUrgencyTier.STRATEGIC;
            case ECONOMIC_CRISIS, SUPPLY_BLOCK -> CoachingUrgencyTier.ECONOMIC;
            default -> null;
        };
    }
}
