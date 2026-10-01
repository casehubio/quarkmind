package io.quarkmind.domain;

import java.util.HashMap;
import java.util.Map;

public enum UpgradeType {
    // --- Terran ability upgrades ---
    STIMPACK("Stimpack"),
    COMBAT_SHIELD("ShieldWall"),
    CONCUSSIVE_SHELLS("PunisherGrenades"),
    BANSHEE_CLOAK("BansheeCloak"),
    BANSHEE_SPEED("BansheeSpeed"),
    BATTLECRUISER_SPECIALIZATIONS("BattlecruiserEnableSpecializations"),
    SMART_SERVOS("SmartServos"),
    HI_SEC_AUTO_TRACKING("HiSecAutoTracking"),
    TERRAN_BUILDING_ARMOR("TerranBuildingArmor"),
    CYCLONE_LOCK_ON_UPGRADE("CycloneLockOnDamageUpgrade"),
    HIGH_CAPACITY_BARRELS("HighCapacityBarrels"),
    LIBERATOR_RANGE("LiberatorAGRangeUpgrade"),
    MEDIVAC_SPEED_BOOST("MedivacIncreaseSpeedBoost"),
    RAVEN_CORVID_REACTOR("RavenCorvidReactor"),
    PERSONAL_CLOAKING("PersonalCloaking"),
    DRILL_CLAWS("DrillClaws"),
    TUNNELING_CLAWS("TunnelingClaws"),

    // --- Terran tiered upgrades ---
    TERRAN_INFANTRY_WEAPONS_1("TerranInfantryWeaponsLevel1"),
    TERRAN_INFANTRY_WEAPONS_2("TerranInfantryWeaponsLevel2"),
    TERRAN_INFANTRY_WEAPONS_3("TerranInfantryWeaponsLevel3"),
    TERRAN_INFANTRY_ARMORS_1("TerranInfantryArmorsLevel1"),
    TERRAN_INFANTRY_ARMORS_2("TerranInfantryArmorsLevel2"),
    TERRAN_INFANTRY_ARMORS_3("TerranInfantryArmorsLevel3"),
    TERRAN_VEHICLE_WEAPONS_1("TerranVehicleWeaponsLevel1"),
    TERRAN_VEHICLE_WEAPONS_2("TerranVehicleWeaponsLevel2"),
    TERRAN_VEHICLE_WEAPONS_3("TerranVehicleWeaponsLevel3"),
    TERRAN_SHIP_WEAPONS_1("TerranShipWeaponsLevel1"),
    TERRAN_SHIP_WEAPONS_2("TerranShipWeaponsLevel2"),
    TERRAN_SHIP_WEAPONS_3("TerranShipWeaponsLevel3"),
    TERRAN_VEHICLE_AND_SHIP_ARMORS_1("TerranVehicleAndShipArmorsLevel1"),
    TERRAN_VEHICLE_AND_SHIP_ARMORS_2("TerranVehicleAndShipArmorsLevel2"),
    TERRAN_VEHICLE_AND_SHIP_ARMORS_3("TerranVehicleAndShipArmorsLevel3"),

    // --- Zerg ability upgrades ---
    ZERGLING_SPEED("zerglingmovementspeed"),
    ZERGLING_ATTACK_SPEED("zerglingattackspeed"),
    GLIAL_RECONSTITUTION("GlialReconstitution"),
    CENTRIFUGAL_HOOKS("CentrificalHooks"),
    BURROW("Burrow"),
    OVERLORD_SPEED("overlordspeed"),
    GROOVED_SPINES("EvolveGroovedSpines"),
    MUSCULAR_AUGMENTS("EvolveMuscularAugments"),
    NEURAL_PARASITE("NeuralParasite"),
    INFESTOR_ENERGY("InfestorEnergyUpgrade"),
    CHITINOUS_PLATING("ChitinousPlating"),
    ANABOLIC_SYNTHESIS("AnabolicSynthesis"),

    // --- Zerg tiered upgrades ---
    ZERG_MELEE_WEAPONS_1("ZergMeleeWeaponsLevel1"),
    ZERG_MELEE_WEAPONS_2("ZergMeleeWeaponsLevel2"),
    ZERG_MELEE_WEAPONS_3("ZergMeleeWeaponsLevel3"),
    ZERG_MISSILE_WEAPONS_1("ZergMissileWeaponsLevel1"),
    ZERG_MISSILE_WEAPONS_2("ZergMissileWeaponsLevel2"),
    ZERG_MISSILE_WEAPONS_3("ZergMissileWeaponsLevel3"),
    ZERG_GROUND_ARMORS_1("ZergGroundArmorsLevel1"),
    ZERG_GROUND_ARMORS_2("ZergGroundArmorsLevel2"),
    ZERG_GROUND_ARMORS_3("ZergGroundArmorsLevel3"),
    ZERG_FLYER_WEAPONS_1("ZergFlyerWeaponsLevel1"),
    ZERG_FLYER_WEAPONS_2("ZergFlyerWeaponsLevel2"),
    ZERG_FLYER_WEAPONS_3("ZergFlyerWeaponsLevel3"),
    ZERG_FLYER_ARMORS_1("ZergFlyerArmorsLevel1"),
    ZERG_FLYER_ARMORS_2("ZergFlyerArmorsLevel2"),
    ZERG_FLYER_ARMORS_3("ZergFlyerArmorsLevel3"),

    // --- Protoss ability upgrades ---
    WARP_GATE_RESEARCH("WarpGateResearch"),
    BLINK("BlinkTech"),
    CHARGE("Charge"),
    ADEPT_PIERCING("AdeptPiercingAttack"),
    PSI_STORM("PsiStormTech"),
    EXTENDED_THERMAL_LANCE("ExtendedThermalLance"),
    DARK_TEMPLAR_BLINK("DarkTemplarBlinkUpgrade"),
    PHOENIX_RANGE("PhoenixRangeUpgrade"),

    // --- Protoss tiered upgrades ---
    PROTOSS_GROUND_WEAPONS_1("ProtossGroundWeaponsLevel1"),
    PROTOSS_GROUND_WEAPONS_2("ProtossGroundWeaponsLevel2"),
    PROTOSS_GROUND_WEAPONS_3("ProtossGroundWeaponsLevel3"),
    PROTOSS_GROUND_ARMORS_1("ProtossGroundArmorsLevel1"),
    PROTOSS_GROUND_ARMORS_2("ProtossGroundArmorsLevel2"),
    PROTOSS_GROUND_ARMORS_3("ProtossGroundArmorsLevel3"),
    PROTOSS_AIR_WEAPONS_1("ProtossAirWeaponsLevel1"),
    PROTOSS_AIR_WEAPONS_2("ProtossAirWeaponsLevel2"),
    PROTOSS_AIR_WEAPONS_3("ProtossAirWeaponsLevel3"),
    PROTOSS_AIR_ARMORS_1("ProtossAirArmorsLevel1"),
    PROTOSS_AIR_ARMORS_2("ProtossAirArmorsLevel2"),
    PROTOSS_AIR_ARMORS_3("ProtossAirArmorsLevel3"),
    PROTOSS_SHIELDS_1("ProtossShieldsLevel1"),
    PROTOSS_SHIELDS_2("ProtossShieldsLevel2"),
    PROTOSS_SHIELDS_3("ProtossShieldsLevel3");

    private final String pythonName;

    private static final Map<String, UpgradeType> BY_PYTHON_NAME;
    static {
        var map = new HashMap<String, UpgradeType>();
        for (UpgradeType ut : values()) map.put(ut.pythonName, ut);
        BY_PYTHON_NAME = Map.copyOf(map);
    }

    UpgradeType(String pythonName) { this.pythonName = pythonName; }

    public String pythonName() { return pythonName; }

    public static UpgradeType fromPythonName(String name) { return BY_PYTHON_NAME.get(name); }
}
