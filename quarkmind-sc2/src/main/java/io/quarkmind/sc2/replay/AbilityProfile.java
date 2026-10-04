package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum AbilityProfile {

    V4_9_3(0, Collections.emptyMap()),
    HSC_2025(2, buildHsc2025MorphOverrides());

    private static final Logger log = Logger.getLogger(AbilityProfile.class);

    private final int abilLinkOffset;
    private final Map<Integer, AbilityDispatch> overrides;

    AbilityProfile(int abilLinkOffset, Map<Integer, AbilityDispatch> overrides) {
        this.abilLinkOffset = abilLinkOffset;
        this.overrides = overrides;
    }

    public int abilLinkOffset() {
        return abilLinkOffset;
    }

    public Map<Integer, AbilityDispatch> overrides() {
        return overrides;
    }

    private static final int BASEBUILD_THRESHOLD_4_9_3 = 75689;

    // Protoss building unitLinks (discovered from HSC XXVII tracker events)
    private static final int UNIT_LINK_CYBERNETICS_CORE = 95;
    private static final int UNIT_LINK_TWILIGHT_COUNCIL = 88;
    private static final int UNIT_LINK_FORGE            = 86;
    private static final int UNIT_LINK_TEMPLAR_ARCHIVE  = 91;
    private static final int UNIT_LINK_ROBOTICS_BAY     = 93;
    private static final int UNIT_LINK_DARK_SHRINE      = 92;
    private static final int UNIT_LINK_FLEET_BEACON     = 87;

    // Zerg building unitLinks (discovered from HSC XXVII tracker events)
    private static final int UNIT_LINK_SPAWNING_POOL     = 112;
    private static final int UNIT_LINK_EVOLUTION_CHAMBER = 113;
    private static final int UNIT_LINK_HYDRALISK_DEN     = 114;
    private static final int UNIT_LINK_SPIRE             = 115;
    private static final int UNIT_LINK_ULTRALISK_CAVERN  = 116;
    private static final int UNIT_LINK_INFESTATION_PIT   = 117;
    private static final int UNIT_LINK_BANELING_NEST     = 119;
    private static final int UNIT_LINK_ROACH_WARREN      = 120;
    private static final int UNIT_LINK_HATCHERY          = 109;

    // Terran building unitLinks (discovered from HSC XXVII tracker events)
    private static final int UNIT_LINK_TECHLAB           = 60;

    public static AbilityProfile resolve(int baseBuild) {
        if (baseBuild <= BASEBUILD_THRESHOLD_4_9_3) { return V4_9_3; }
        return HSC_2025;
    }

    private static List<ReplayCommand> upgradeCmd(long loop, String name) {
        return List.of(new ReplayCommand.UpgradeCommand(loop, name));
    }


    private static Map<Integer, AbilityDispatch> buildHsc2025MorphOverrides() {
        Map<Integer, AbilityDispatch> m = new HashMap<>();
        m.put(730, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Zergling", "Baneling")) : null);
        m.put(311, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Roach", "Ravager")) : null);
        m.put(196, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Corruptor", "BroodLord")) : null);
        m.put(524, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Hydralisk", "Lurker")) : null);
        m.put(223, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG || idx != 0) { return null; }
            return List.of(new ReplayCommand.MorphCommand(loop, "Overlord", "Overseer"));
        });
        return Collections.unmodifiableMap(m);
    }

    // buildHsc2025Overrides() removed — replaced by abilLinkOffset mechanism.
    // The override approach (generic abilLink dispatchers 177/195/216/220/723/234/605) caused
    // 700+ false positive upgrade detections because the same abilLink values are used for
    // unit ability activations in newer patches. See git history (commit 358c5d0b) for the
    // original implementation. Morph overrides retained in buildHsc2025MorphOverrides().
    @SuppressWarnings("unused")
    private static Map<Integer, AbilityDispatch> buildHsc2025Overrides() {
        Map<Integer, AbilityDispatch> m = new HashMap<>();

        m.put(730, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Zergling", "Baneling")) : null);
        m.put(311, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Roach", "Ravager")) : null);
        m.put(196, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Corruptor", "BroodLord")) : null);
        m.put(524, (idx, event, loop, race, unitLink) -> race == Race.ZERG
                ? List.of(new ReplayCommand.MorphCommand(loop, "Hydralisk", "Lurker")) : null);

        m.put(223, (idx, event, loop, race, unitLink) -> {
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
        m.put(164, (idx, event, loop, race, unitLink) -> race == Race.TERRAN && engBayTournament.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, engBayTournament.get(idx))) : null);

        m.put(191, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "EvolveGroovedSpines"));
                case 1 -> List.of(new ReplayCommand.UpgradeCommand(loop, "EvolveMuscularAugments"));
                default -> null;
            };
        });

        m.put(165, (idx, event, loop, race, unitLink) -> {
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
        m.put(182, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && forgeUpgrades.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, forgeUpgrades.get(idx))) : null);

        m.put(192, (idx, event, loop, race, unitLink) -> {
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
        m.put(174, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && cyberUpgrades.containsKey(idx)
                ? List.of(new ReplayCommand.UpgradeCommand(loop, cyberUpgrades.get(idx))) : null);

        Map<Integer, String> twilightUpgrades = Map.of(0, "Charge", 1, "BlinkTech", 2, "AdeptPiercingAttack");

        // 177: Generic Protoss research abilLink in tournament replays.
        // Fires from any Protoss building — disambiguate by selection unitLink.
        m.put(177, (idx, event, loop, race, unitLink) -> {
            if (race != Race.PROTOSS) { return null; }
            return switch (unitLink) {
                case UNIT_LINK_CYBERNETICS_CORE -> cyberUpgrades.containsKey(idx) ? upgradeCmd(loop, cyberUpgrades.get(idx)) : null;
                case UNIT_LINK_TWILIGHT_COUNCIL -> twilightUpgrades.containsKey(idx) ? upgradeCmd(loop, twilightUpgrades.get(idx)) : null;
                case UNIT_LINK_FORGE -> forgeUpgrades.containsKey(idx) ? upgradeCmd(loop, forgeUpgrades.get(idx)) : null;
                case UNIT_LINK_TEMPLAR_ARCHIVE -> idx == 4 ? upgradeCmd(loop, "PsiStormTech") : null;
                case UNIT_LINK_ROBOTICS_BAY -> idx == 5 ? upgradeCmd(loop, "ExtendedThermalLance") : null;
                case UNIT_LINK_DARK_SHRINE -> idx == 0 ? upgradeCmd(loop, "DarkTemplarBlinkUpgrade") : null;
                case UNIT_LINK_FLEET_BEACON -> idx == 2 ? upgradeCmd(loop, "PhoenixRangeUpgrade") : null;
                default -> null;
            };
        });

        Map<Integer, String> evoChamberUpgrades = Map.ofEntries(
                Map.entry(0, "ZergMeleeWeaponsLevel1"), Map.entry(1, "ZergMeleeWeaponsLevel2"),
                Map.entry(2, "ZergMeleeWeaponsLevel3"), Map.entry(3, "ZergGroundArmorsLevel1"),
                Map.entry(4, "ZergGroundArmorsLevel2"), Map.entry(5, "ZergGroundArmorsLevel3"),
                Map.entry(6, "ZergMissileWeaponsLevel1"), Map.entry(7, "ZergMissileWeaponsLevel2"),
                Map.entry(8, "ZergMissileWeaponsLevel3"));
        Map<Integer, String> spireUpgrades = Map.ofEntries(
                Map.entry(0, "ZergFlyerWeaponsLevel1"), Map.entry(1, "ZergFlyerWeaponsLevel2"),
                Map.entry(2, "ZergFlyerWeaponsLevel3"), Map.entry(3, "ZergFlyerArmorsLevel1"),
                Map.entry(4, "ZergFlyerArmorsLevel2"), Map.entry(5, "ZergFlyerArmorsLevel3"));

        // 195: Generic Zerg research abilLink in tournament replays.
        // Fires from any Zerg building — disambiguate by selection unitLink.
        m.put(195, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG) { return null; }
            return switch (unitLink) {
                case UNIT_LINK_SPAWNING_POOL -> switch (idx) {
                    case 0 -> upgradeCmd(loop, "zerglingattackspeed");
                    case 1 -> upgradeCmd(loop, "zerglingmovementspeed");
                    default -> null;
                };
                case UNIT_LINK_EVOLUTION_CHAMBER -> evoChamberUpgrades.containsKey(idx) ? upgradeCmd(loop, evoChamberUpgrades.get(idx)) : null;
                case UNIT_LINK_HYDRALISK_DEN -> switch (idx) {
                    case 0 -> upgradeCmd(loop, "EvolveGroovedSpines");
                    case 1 -> upgradeCmd(loop, "EvolveMuscularAugments");
                    default -> null;
                };
                case UNIT_LINK_BANELING_NEST -> idx == 0 ? upgradeCmd(loop, "CentrificalHooks") : null;
                case UNIT_LINK_ROACH_WARREN -> switch (idx) {
                    case 1 -> upgradeCmd(loop, "GlialReconstitution");
                    case 2 -> upgradeCmd(loop, "TunnelingClaws");
                    default -> null;
                };
                case UNIT_LINK_ULTRALISK_CAVERN -> switch (idx) {
                    case 0 -> upgradeCmd(loop, "AnabolicSynthesis");
                    case 2 -> upgradeCmd(loop, "ChitinousPlating");
                    default -> null;
                };
                case UNIT_LINK_INFESTATION_PIT -> switch (idx) {
                    case 2 -> upgradeCmd(loop, "InfestorEnergyUpgrade");
                    case 3 -> upgradeCmd(loop, "NeuralParasite");
                    default -> null;
                };
                case UNIT_LINK_HATCHERY -> switch (idx) {
                    case 1 -> upgradeCmd(loop, "overlordspeed");
                    case 3 -> upgradeCmd(loop, "Burrow");
                    default -> null;
                };
                case UNIT_LINK_SPIRE -> spireUpgrades.containsKey(idx) ? upgradeCmd(loop, spireUpgrades.get(idx)) : null;
                default -> null;
            };
        });

        // 216, 220, 723, 234, 605: Additional generic Protoss research abilLinks in tournament replays.
        // Same unitLink dispatch as abilLink 177.
        AbilityDispatch protossGenericResearch = (idx, event, loop, race, unitLink) -> {
            if (race != Race.PROTOSS) { return null; }
            return switch (unitLink) {
                case UNIT_LINK_CYBERNETICS_CORE -> cyberUpgrades.containsKey(idx) ? upgradeCmd(loop, cyberUpgrades.get(idx)) : null;
                case UNIT_LINK_TWILIGHT_COUNCIL -> twilightUpgrades.containsKey(idx) ? upgradeCmd(loop, twilightUpgrades.get(idx)) : null;
                case UNIT_LINK_FORGE -> forgeUpgrades.containsKey(idx) ? upgradeCmd(loop, forgeUpgrades.get(idx)) : null;
                case UNIT_LINK_TEMPLAR_ARCHIVE -> idx == 4 ? upgradeCmd(loop, "PsiStormTech") : null;
                case UNIT_LINK_ROBOTICS_BAY -> idx == 5 ? upgradeCmd(loop, "ExtendedThermalLance") : null;
                case UNIT_LINK_DARK_SHRINE -> idx == 0 ? upgradeCmd(loop, "DarkTemplarBlinkUpgrade") : null;
                case UNIT_LINK_FLEET_BEACON -> idx == 2 ? upgradeCmd(loop, "PhoenixRangeUpgrade") : null;
                default -> null;
            };
        };
        m.put(216, protossGenericResearch);
        m.put(220, protossGenericResearch);
        m.put(723, protossGenericResearch);
        m.put(234, protossGenericResearch);
        m.put(605, protossGenericResearch);

        // --- Building-specific tournament abilLinks (discovered via building-filtered diagnostic) ---

        // 238: CyberneticsCore tournament (WarpGateResearch at idx=6, air weapons/armor at idx=0-5)
        m.put(238, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && cyberUpgrades.containsKey(idx)
                ? upgradeCmd(loop, cyberUpgrades.get(idx)) : null);

        // 239: TwilightCouncil tournament (Charge=0, BlinkTech=1, AdeptPiercing=2)
        m.put(239, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && twilightUpgrades.containsKey(idx)
                ? upgradeCmd(loop, twilightUpgrades.get(idx)) : null);

        // 184: TemplarArchive/Hatchery in tournament (PsiStormTech at idx=4)
        m.put(184, (idx, event, loop, race, unitLink) -> {
            if (race == Race.PROTOSS && idx == 4) { return upgradeCmd(loop, "PsiStormTech"); }
            return null;
        });

        // 183: RoboticsBay tournament (ExtendedThermalLance at idx=5)
        m.put(183, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && idx == 5
                ? upgradeCmd(loop, "ExtendedThermalLance") : null);

        // 187: EvolutionChamber tournament (melee/missile weapons + ground armor by idx)
        m.put(187, (idx, event, loop, race, unitLink) -> race == Race.ZERG && evoChamberUpgrades.containsKey(idx)
                ? upgradeCmd(loop, evoChamberUpgrades.get(idx)) : null);

        // 226: BanelingNest tournament (CentrificalHooks at idx=0)
        m.put(226, (idx, event, loop, race, unitLink) -> race == Race.ZERG && idx == 0
                ? upgradeCmd(loop, "CentrificalHooks") : null);

        // 109: RoachWarren tournament (GlialReconstitution=1, TunnelingClaws=2)
        m.put(109, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 1 -> upgradeCmd(loop, "GlialReconstitution");
                case 2 -> upgradeCmd(loop, "TunnelingClaws");
                default -> null;
            };
        });

        // 193: HydraliskDen tournament (GroovedSpines=0, MuscularAugments=1) — 193 was Larva in 4.9.3
        // Gated on unitLink to avoid colliding with Larva train (same abilLink, different building)
        m.put(193, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG || unitLink != UNIT_LINK_HYDRALISK_DEN) { return null; }
            return switch (idx) {
                case 0 -> upgradeCmd(loop, "EvolveGroovedSpines");
                case 1 -> upgradeCmd(loop, "EvolveMuscularAugments");
                default -> null;
            };
        });

        // 225: InfestationPit tournament (NeuralParasite at idx=3)
        m.put(225, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 2 -> upgradeCmd(loop, "InfestorEnergyUpgrade");
                case 3 -> upgradeCmd(loop, "NeuralParasite");
                default -> null;
            };
        });

        // 167: TechLab tournament — Stimpack=1, ShieldWall=1, PunisherGrenades=2, BansheeCloak=0, DrillClaws=2
        // TechLabs share unitLink=60 so idx disambiguates; some idx values are shared across TechLab types
        m.put(167, (idx, event, loop, race, unitLink) -> {
            if (race != Race.TERRAN) { return null; }
            return switch (idx) {
                case 0 -> upgradeCmd(loop, "BansheeCloak");
                case 1 -> upgradeCmd(loop, "Stimpack");
                case 2 -> upgradeCmd(loop, "PunisherGrenades");
                default -> null;
            };
        });

        // 171: Armory tournament — vehicle/ship weapons and armor by idx
        Map<Integer, String> armoryTournament = Map.ofEntries(
                Map.entry(5, "TerranVehicleWeaponsLevel1"), Map.entry(6, "TerranVehicleWeaponsLevel2"),
                Map.entry(7, "TerranVehicleWeaponsLevel3"),
                Map.entry(11, "TerranShipWeaponsLevel1"), Map.entry(12, "TerranShipWeaponsLevel2"),
                Map.entry(13, "TerranShipWeaponsLevel3"),
                Map.entry(14, "TerranVehicleAndShipArmorsLevel1"), Map.entry(15, "TerranVehicleAndShipArmorsLevel2"),
                Map.entry(16, "TerranVehicleAndShipArmorsLevel3"));
        m.put(171, (idx, event, loop, race, unitLink) -> race == Race.TERRAN && armoryTournament.containsKey(idx)
                ? upgradeCmd(loop, armoryTournament.get(idx)) : null);

        // 245: SpawningPool in tournament — zerglingmovementspeed (6 single-sel hits)
        m.put(245, (idx, event, loop, race, unitLink) -> {
            if (race != Race.ZERG) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "zerglingmovementspeed"));
                case 1 -> List.of(new ReplayCommand.UpgradeCommand(loop, "zerglingattackspeed"));
                default -> null;
            };
        });

        // 399: BarracksTechLab tournament variant — PunisherGrenades (3 single-sel hits)
        m.put(399, (idx, event, loop, race, unitLink) -> {
            if (race != Race.TERRAN) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "PunisherGrenades"));
                default -> null;
            };
        });

        // 406: BarracksTechLab tournament variant — ShieldWall (3 single-sel hits)
        m.put(406, (idx, event, loop, race, unitLink) -> {
            if (race != Race.TERRAN) { return null; }
            return switch (idx) {
                case 0 -> List.of(new ReplayCommand.UpgradeCommand(loop, "ShieldWall"));
                default -> null;
            };
        });

        // 716: TwilightCouncil tournament variant — Charge (2 single-sel hits)
        m.put(716, (idx, event, loop, race, unitLink) -> race == Race.PROTOSS && idx == 0
                ? List.of(new ReplayCommand.UpgradeCommand(loop, "Charge")) : null);

        // 157: Stimpack tournament variant (4 single-sel hits)
        m.put(157, (idx, event, loop, race, unitLink) -> race == Race.TERRAN && idx == 0
                ? List.of(new ReplayCommand.UpgradeCommand(loop, "Stimpack")) : null);

        return Collections.unmodifiableMap(m);
    }
}
