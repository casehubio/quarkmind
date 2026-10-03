package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum AbilityProfile {

    V4_9_3(Collections.emptyMap()),
    HSC_2025(buildHsc2025Overrides());

    private static final Logger log = Logger.getLogger(AbilityProfile.class);

    private final Map<Integer, AbilityDispatch> overrides;

    AbilityProfile(Map<Integer, AbilityDispatch> overrides) {
        this.overrides = overrides;
    }

    public Map<Integer, AbilityDispatch> overrides() {
        return overrides;
    }

    private static final int BASEBUILD_THRESHOLD_4_9_3 = 75689;

    public static AbilityProfile resolve(int baseBuild) {
        if (baseBuild <= BASEBUILD_THRESHOLD_4_9_3) { return V4_9_3; }
        return HSC_2025;
    }

    private static Map<Integer, AbilityDispatch> buildHsc2025Overrides() {
        Map<Integer, AbilityDispatch> m = new HashMap<>();

        m.put(730, (idx, event, loop, race) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Zergling", "Baneling")) : null);
        m.put(311, (idx, event, loop, race) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Roach", "Ravager")) : null);
        m.put(196, (idx, event, loop, race) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Corruptor", "BroodLord")) : null);
        m.put(524, (idx, event, loop, race) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Hydralisk", "Lurker")) : null);

        m.put(223, (idx, event, loop, race) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.MorphCommand(loop, "Overlord", "Overseer"));
                case 2 -> List.of(new ReplayCommand.UpgradeCommand(loop, "InfestorEnergyUpgrade"));
                case 3 -> List.of(new ReplayCommand.UpgradeCommand(loop, "NeuralParasite"));
                default -> null;
            };
        });

        Map<Integer, String> engBayTournament = Map.ofEntries(
                Map.entry(0, "HiSecAutoTracking"), Map.entry(1, "TerranBuildingArmor"),
                Map.entry(2, "TerranInfantryWeaponsLevel1"), Map.entry(3, "TerranInfantryWeaponsLevel2"),
                Map.entry(4, "TerranInfantryWeaponsLevel3"),
                Map.entry(6, "TerranInfantryArmorsLevel1"), Map.entry(7, "TerranInfantryArmorsLevel2"),
                Map.entry(8, "TerranInfantryArmorsLevel3"));
        m.put(164, (idx, event, loop, race) -> race == Race.TERRAN && engBayTournament.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, engBayTournament.get(idx))) : null);

        m.put(191, (idx, event, loop, race) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "EvolveGroovedSpines"));
                case 1 -> List.of(new ReplayCommand.UpgradeCommand(loop, "EvolveMuscularAugments"));
                default -> null;
            };
        });

        m.put(165, (idx, event, loop, race) -> {
            if (race != Race.TERRAN) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "Stimpack"));
                case 1 -> List.of(new ReplayCommand.UpgradeCommand(loop, "ShieldWall"));
                case 2 -> List.of(new ReplayCommand.UpgradeCommand(loop, "PunisherGrenades"));
                default -> null;
            };
        });

        Map<Integer, String> forgeUpgrades = Map.ofEntries(
                Map.entry(0, "ProtossGroundWeaponsLevel1"), Map.entry(1, "ProtossGroundWeaponsLevel2"),
                Map.entry(2, "ProtossGroundWeaponsLevel3"), Map.entry(3, "ProtossGroundArmorsLevel1"),
                Map.entry(4, "ProtossGroundArmorsLevel2"), Map.entry(5, "ProtossGroundArmorsLevel3"),
                Map.entry(6, "ProtossShieldsLevel1"), Map.entry(7, "ProtossShieldsLevel2"),
                Map.entry(8, "ProtossShieldsLevel3"));
        m.put(182, (idx, event, loop, race) -> race == Race.PROTOSS && forgeUpgrades.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, forgeUpgrades.get(idx))) : null);

        m.put(192, (idx, event, loop, race) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "zerglingattackspeed"));
                case 1 -> List.of(new ReplayCommand.UpgradeCommand(loop, "zerglingmovementspeed"));
                default -> null;
            };
        });

        Map<Integer, String> cyberUpgrades = Map.ofEntries(
                Map.entry(0, "ProtossAirWeaponsLevel1"), Map.entry(1, "ProtossAirWeaponsLevel2"),
                Map.entry(2, "ProtossAirWeaponsLevel3"), Map.entry(3, "ProtossAirArmorsLevel1"),
                Map.entry(4, "ProtossAirArmorsLevel2"), Map.entry(5, "ProtossAirArmorsLevel3"),
                Map.entry(6, "WarpGateResearch"));
        m.put(174, (idx, event, loop, race) -> race == Race.PROTOSS && cyberUpgrades.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, cyberUpgrades.get(idx))) : null);

        return Collections.unmodifiableMap(m);
    }
}
