package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvisoryTriggerBuilderTest {

    @Test
    void nexusUnderAttackMoment_producesCrisisTrigger() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 4200L, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 4200L);

        assertTrue(triggers.containsKey("game.advisory.trigger.crisis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> crisisTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.crisis");
        assertEquals(4200L, crisisTrigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) crisisTrigger.get("momentTypes");
        assertEquals(List.of("NEXUS_UNDER_ATTACK"), momentTypes);
    }

    @Test
    void economicCrisisMoment_producesEconomicTrigger() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.ECONOMIC_CRISIS, 3600L, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 3600L);

        assertTrue(triggers.containsKey("game.advisory.trigger.economic"));
        @SuppressWarnings("unchecked")
        Map<String, Object> economicTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.economic");
        assertEquals(3600L, economicTrigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) economicTrigger.get("momentTypes");
        assertEquals(List.of("ECONOMIC_CRISIS"), momentTypes);
    }

    @Test
    void techTransitionMoment_producesStrategicTrigger() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.TECH_TRANSITION_DETECTED, 5000L, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 5000L);

        assertTrue(triggers.containsKey("game.advisory.trigger.strategic"));
        @SuppressWarnings("unchecked")
        Map<String, Object> strategicTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.strategic");
        assertEquals(5000L, strategicTrigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) strategicTrigger.get("momentTypes");
        assertEquals(List.of("TECH_TRANSITION_DETECTED"), momentTypes);
    }

    @Test
    void multipleMomentsOfDifferentRoles_produceMultipleTriggers() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(
                new GameMoment(GameMomentType.BATTLE_STARTED, 4800L, Map.of()),
                new GameMoment(GameMomentType.SUPPLY_BLOCK, 4850L, Map.of()),
                new GameMoment(GameMomentType.TECH_TRANSITION_DETECTED, 4900L, Map.of())
            )
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 4900L);

        assertEquals(3, triggers.size());
        assertTrue(triggers.containsKey("game.advisory.trigger.crisis"));
        assertTrue(triggers.containsKey("game.advisory.trigger.economic"));
        assertTrue(triggers.containsKey("game.advisory.trigger.strategic"));

        @SuppressWarnings("unchecked")
        Map<String, Object> crisisTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.crisis");
        assertEquals(4900L, crisisTrigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> crisisMomentTypes = (List<String>) crisisTrigger.get("momentTypes");
        assertEquals(List.of("BATTLE_STARTED"), crisisMomentTypes);

        @SuppressWarnings("unchecked")
        Map<String, Object> economicTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.economic");
        @SuppressWarnings("unchecked")
        List<String> economicMomentTypes = (List<String>) economicTrigger.get("momentTypes");
        assertEquals(List.of("SUPPLY_BLOCK"), economicMomentTypes);

        @SuppressWarnings("unchecked")
        Map<String, Object> strategicTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.strategic");
        @SuppressWarnings("unchecked")
        List<String> strategicMomentTypes = (List<String>) strategicTrigger.get("momentTypes");
        assertEquals(List.of("TECH_TRANSITION_DETECTED"), strategicMomentTypes);
    }

    @Test
    void noMatchingMoments_producesEmptyMap() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.FIRST_CONTACT, 2400L, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 2400L);

        assertTrue(triggers.isEmpty());
    }

    @Test
    void nullMomentsList_producesEmptyMap() {
        CaseContext ctx = new MapCaseContext(Map.of());

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 1000L);

        assertTrue(triggers.isEmpty());
    }

    @Test
    void multipleMomentsOfSameRole_consolidatedIntoSingleTrigger() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(
                new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 4200L, Map.of()),
                new GameMoment(GameMomentType.BATTLE_STARTED, 4250L, Map.of())
            )
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 4250L);

        assertEquals(1, triggers.size());
        assertTrue(triggers.containsKey("game.advisory.trigger.crisis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> crisisTrigger = (Map<String, Object>) triggers.get("game.advisory.trigger.crisis");
        assertEquals(4250L, crisisTrigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) crisisTrigger.get("momentTypes");
        assertEquals(2, momentTypes.size());
        assertTrue(momentTypes.contains("NEXUS_UNDER_ATTACK"));
        assertTrue(momentTypes.contains("BATTLE_STARTED"));
    }
}
