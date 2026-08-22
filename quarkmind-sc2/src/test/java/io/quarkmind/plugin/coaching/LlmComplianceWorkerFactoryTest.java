package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LlmComplianceWorkerFactoryTest {

    @Test
    void summarise_unitAddsAndRemovals_renderedCorrectly() {
        var baseline = state(450, 200, 46, 38,
            List.of(unit(UnitType.STALKER), unit(UnitType.STALKER), unit(UnitType.ZEALOT)),
            List.of(building(BuildingType.NEXUS), building(BuildingType.GATEWAY)),
            1200);
        var current = state(280, 150, 62, 52,
            List.of(unit(UnitType.STALKER), unit(UnitType.STALKER), unit(UnitType.STALKER),
                    unit(UnitType.STALKER), unit(UnitType.ZEALOT), unit(UnitType.SENTRY)),
            List.of(building(BuildingType.NEXUS), building(BuildingType.NEXUS), building(BuildingType.GATEWAY)),
            1650);

        String result = LlmComplianceWorkerFactory.summariseForCompliance(baseline, current, "Build more Stalkers");

        assertTrue(result.contains("ADVICE: \"Build more Stalkers\""));
        assertTrue(result.contains("BEFORE"));
        assertTrue(result.contains("AFTER"));
        assertTrue(result.contains("CHANGES"));
        assertTrue(result.contains("+2x STALKER"));
        assertTrue(result.contains("+1x SENTRY"));
        assertTrue(result.contains("+1x NEXUS"));
    }

    @Test
    void summarise_noChanges_changesShowsNone() {
        var s = state(400, 200, 46, 38,
            List.of(unit(UnitType.STALKER)),
            List.of(building(BuildingType.NEXUS)),
            1200);

        String result = LlmComplianceWorkerFactory.summariseForCompliance(s, s, "Do something");

        assertTrue(result.contains("CHANGES"));
        assertTrue(result.contains("No unit or building changes"));
    }

    @Test
    void summarise_emptyArmy_handledGracefully() {
        var baseline = state(400, 200, 46, 38, List.of(), List.of(), 1200);
        var current = state(300, 150, 54, 44,
            List.of(unit(UnitType.STALKER), unit(UnitType.STALKER)),
            List.of(), 1650);

        String result = LlmComplianceWorkerFactory.summariseForCompliance(baseline, current, "Build units");

        assertTrue(result.contains("+2x STALKER"));
    }

    @Test
    void summarise_unitRemovals_renderedAsNegative() {
        var baseline = state(400, 200, 46, 38,
            List.of(unit(UnitType.STALKER), unit(UnitType.STALKER), unit(UnitType.STALKER)),
            List.of(), 1200);
        var current = state(400, 200, 46, 38,
            List.of(unit(UnitType.STALKER)),
            List.of(), 1650);

        String result = LlmComplianceWorkerFactory.summariseForCompliance(baseline, current, "Hold position");

        assertTrue(result.contains("-2x STALKER"));
    }

    @Test
    void summarise_resourceDelta_rendered() {
        var baseline = state(450, 200, 46, 38, List.of(), List.of(), 1200);
        var current = state(280, 150, 62, 52, List.of(), List.of(), 1650);

        String result = LlmComplianceWorkerFactory.summariseForCompliance(baseline, current, "Spend resources");

        assertTrue(result.contains("Minerals: -170"));
        assertTrue(result.contains("Vespene: -50"));
    }

    @Test
    void buildSystemPrompt_containsVerdictVocabulary() {
        String prompt = LlmComplianceWorkerFactory.buildSystemPrompt();

        assertTrue(prompt.contains("COMPLIED"));
        assertTrue(prompt.contains("PARTIALLY"));
        assertTrue(prompt.contains("IGNORED"));
        assertTrue(prompt.contains("verdict"));
        assertTrue(prompt.contains("confidence"));
        assertTrue(prompt.contains("reasoning"));
    }

    private static Unit unit(UnitType type) {
        return new Unit("tag-" + type.name(), type, new Point2d(0, 0), 100, 100, 50, 50, 0, 0);
    }

    private static Building building(BuildingType type) {
        return new Building("tag-" + type.name(), type, new Point2d(0, 0), 1000, 1000, true);
    }

    private static GameState state(int minerals, int vespene, int supply, int supplyUsed,
                                   List<Unit> units, List<Building> buildings, long frame) {
        return new GameState(minerals, vespene, supply, supplyUsed, units, buildings, List.of(), List.of(), List.of(), List.of(), List.of(), frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }
}
