package io.quarkmind.sc2.playbook;

import io.casehub.pages.playbook.DeliveryContext;
import io.casehub.pages.playbook.DeliveryHandler;
import io.casehub.pages.playbook.StepOutcome;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.intent.AbilityIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.MorphIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TrainIntent;

import java.util.ArrayList;
import java.util.Map;

public class SC2DeliveryHandler implements DeliveryHandler {

    private final EmulatedGame game;

    public SC2DeliveryHandler(EmulatedGame game) {
        this.game = game;
    }

    @Override
    public String name() { return "sc2"; }

    @Override
    public StepOutcome execute(String stepName, Map<String, Object> data, DeliveryContext ctx) {
        String action = (String) data.get("action");
        return switch (action) {
            case "train" -> doTrain(stepName, data);
            case "build" -> doBuild(stepName, data);
            case "research" -> doResearch(stepName, data);
            case "morph" -> doMorph(stepName, data);
            case "ability" -> doAbility(stepName, data);
            case "calldown" -> doCalldown(stepName, data);
            case "assert" -> doAssert(stepName, data);
            default -> StepOutcome.fail(stepName, "Unknown SC2 action: " + action);
        };
    }

    private StepOutcome doTrain(String stepName, Map<String, Object> data) {
        UnitType unit = UnitType.valueOf((String) data.get("train"));
        double mineralsBefore = game.snapshot().minerals();
        int cost = SC2Data.mineralCost(unit);
        if ((int) mineralsBefore < cost) {
            return StepOutcome.fail(stepName, "insufficient minerals for " + unit);
        }
        if (game.snapshot().supplyUsed() + SC2Data.supplyCost(unit) > game.snapshot().supply()) {
            return StepOutcome.fail(stepName, "supply blocked for " + unit);
        }
        String tag = "r-playbook-" + unit.name().toLowerCase();
        game.applyIntent(new TrainIntent(tag, unit));
        if ((int) game.snapshot().minerals() == (int) mineralsBefore && cost > 0) {
            return StepOutcome.fail(stepName, "train rejected by game for " + unit);
        }
        return StepOutcome.ok(stepName, Map.of("unit", unit.name()));
    }

    private StepOutcome doBuild(String stepName, Map<String, Object> data) {
        BuildingType bt = BuildingType.valueOf((String) data.get("build"));
        int mineralsBefore = (int) game.snapshot().minerals();
        int cost = SC2Data.mineralCost(bt);
        if (mineralsBefore < cost) {
            return StepOutcome.fail(stepName, "insufficient minerals for " + bt);
        }
        game.applyIntent(new BuildIntent("r-playbook", bt, new Point2d(30, 30)));
        return StepOutcome.ok(stepName, Map.of("building", bt.name()));
    }

    private StepOutcome doResearch(String stepName, Map<String, Object> data) {
        UpgradeType ut = UpgradeType.valueOf((String) data.get("research"));
        game.applyIntent(new ResearchIntent("r-playbook", ut));
        return StepOutcome.ok(stepName, Map.of("upgrade", ut.name()));
    }

    private StepOutcome doMorph(String stepName, Map<String, Object> data) {
        String target = (String) data.get("morph");
        String source = (String) data.get("source");
        game.applyIntent(new MorphIntent("r-playbook", source, target));
        return StepOutcome.ok(stepName, Map.of("morph", target));
    }


    private StepOutcome doAbility(String stepName, Map<String, Object> data) {
        String        ability    = (String) data.get("ability");
        String        targetType = (String) data.get("target");
        String        targetTag  = (targetType != null) ? "r-" + targetType.toLowerCase() : null;
        String        casterTag  = (targetTag != null) ? targetTag : "r-ability";
        AbilityIntent intent     = new AbilityIntent(casterTag, ability, targetTag);
        boolean       accepted   = game.applyAbility(intent);
        if (!accepted) {
            return StepOutcome.fail(stepName, "ability rejected: " + ability);
        }
        return StepOutcome.ok(stepName, Map.of("ability", ability));
    }

    private StepOutcome doCalldown(String stepName, Map<String, Object> data) {
        String        calldownType = (String) data.get("calldown");
        AbilityIntent intent       = new AbilityIntent("r-orbital_command", "MULE_CALLDOWN", null);
        boolean       accepted     = game.applyAbility(intent);
        if (!accepted) {
            return StepOutcome.fail(stepName, "calldown rejected: " + calldownType);
        }
        return StepOutcome.ok(stepName, Map.of("calldown", calldownType));
    }


    @SuppressWarnings("unchecked")
    private StepOutcome doAssert(String stepName, Map<String, Object> data) {
        var expect = (Map<String, Object>) data.get("expect");
        if (expect == null) return StepOutcome.ok(stepName, Map.of());
        GameState state = game.snapshot();
        System.out.println("[PLAYBOOK] " + stepName
            + " units=" + state.myUnits().size()
            + " buildings=" + state.myBuildings().size()
            + " minerals=" + (int) state.minerals()
            + " supply=" + (int) state.supplyUsed() + "/" + state.supply());
        var errors = new ArrayList<String>();

        if (expect.containsKey("units")) {
            var unitExpect = (Map<String, Map<String, Integer>>) expect.get("units");
            for (var entry : unitExpect.entrySet()) {
                UnitType ut = UnitType.valueOf(entry.getKey());
                int actual = (int) state.myUnits().stream()
                    .filter(u -> u.type() == ut).count();
                var bounds = entry.getValue();
                if (bounds.containsKey("min") && actual < bounds.get("min")) {
                    errors.add(ut + " expected >=" + bounds.get("min") + " but was " + actual);
                }
                if (bounds.containsKey("max") && actual > bounds.get("max")) {
                    errors.add(ut + " expected <=" + bounds.get("max") + " but was " + actual);
                }
            }
        }

        if (expect.containsKey("minerals")) {
            var mineralBounds = (Map<String, Integer>) expect.get("minerals");
            int actual = (int) state.minerals();
            if (mineralBounds.containsKey("min") && actual < mineralBounds.get("min")) {
                errors.add("minerals expected >=" + mineralBounds.get("min") + " but was " + actual);
            }
        }

        if (!errors.isEmpty()) {
            throw new AssertionError(stepName + " checkpoint failed: " + String.join("; ", errors));
        }
        return StepOutcome.ok(stepName, Map.of("passed", true));
    }
}
