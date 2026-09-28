package io.quarkmind.domain;

import java.util.HashMap;
import java.util.Map;

public enum UpgradeType {
    STIMPACK("Stimpack"),
    COMBAT_SHIELD("ShieldWall"),
    CONCUSSIVE_SHELLS("PunisherGrenades"),
    BANSHEE_CLOAK("BansheeCloak"),
    TERRAN_VEHICLE_WEAPONS_1("TerranVehicleWeaponsLevel1"),
    PERSONAL_CLOAKING("PersonalCloaking"),
    DRILL_CLAWS("DrillClaws"),
    ZERGLING_SPEED("zerglingmovementspeed"),
    GLIAL_RECONSTITUTION("GlialReconstitution"),
    CENTRIFUGAL_HOOKS("CentrificalHooks"),
    BURROW("Burrow"),
    WARP_GATE_RESEARCH("WarpGateResearch"),
    BLINK("BlinkTech"),
    CHARGE("Charge"),
    ADEPT_PIERCING("AdeptPiercingAttack");

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
