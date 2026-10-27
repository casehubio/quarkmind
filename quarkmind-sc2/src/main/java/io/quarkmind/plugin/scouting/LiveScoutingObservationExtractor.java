package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.ScoutingObservation;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class LiveScoutingObservationExtractor {

    private static final Set<BuildingType> SUPPLY_BUILDINGS = EnumSet.of(
        BuildingType.PYLON, BuildingType.SUPPLY_DEPOT);
    private static final Set<BuildingType> GAS_BUILDINGS = EnumSet.of(
        BuildingType.ASSIMILATOR, BuildingType.ASSIMILATOR_RICH,
        BuildingType.EXTRACTOR, BuildingType.REFINERY);
    private static final Set<UnitType> LIGHT_UNITS = EnumSet.of(
        UnitType.MARINE, UnitType.ZERGLING, UnitType.ZEALOT,
        UnitType.ADEPT, UnitType.HELLION);
    private static final Set<UnitType> ARMORED_UNITS = EnumSet.of(
        UnitType.ROACH, UnitType.SIEGE_TANK, UnitType.STALKER,
        UnitType.IMMORTAL, UnitType.MARAUDER);
    private static final Set<UnitType> AIR_UNITS = EnumSet.of(
        UnitType.MUTALISK, UnitType.BANSHEE, UnitType.PHOENIX,
        UnitType.VOID_RAY, UnitType.ORACLE, UnitType.LIBERATOR,
        UnitType.VIKING, UnitType.TEMPEST, UnitType.CARRIER,
        UnitType.BROOD_LORD, UnitType.CORRUPTOR, UnitType.VIPER,
        UnitType.MEDIVAC, UnitType.RAVEN, UnitType.BATTLECRUISER,
        UnitType.WARP_PRISM, UnitType.OBSERVER, UnitType.MOTHERSHIP);

    static final long GAS_EARLY = 2016;
    static final long GAS_LATE = 3360;
    static final long EXPAND_FAST = 2688;
    static final long EXPAND_LATE = 4704;
    static final long AGGRESSION_EARLY = 4032;
    static final long AGGRESSION_LATE = 6720;

    private boolean firstBuildingEmitted;
    private boolean gasTimingEmitted;
    private boolean expansionTimingEmitted;
    private boolean techPathEmitted;
    private String lastArmyComposition;
    private String lastWorkerSaturation;
    private String lastAggressionTiming;

    public List<ScoutingObservation> extract(GameState gs) {
        List<ScoutingObservation> observations = new ArrayList<>();
        long frame = gs.gameFrame();

        if (!firstBuildingEmitted) {
            for (Building b : gs.enemyBuildings()) {
                if (!SC2Data.isBase(b.type()) && !SUPPLY_BUILDINGS.contains(b.type())
                        && !GAS_BUILDINGS.contains(b.type())) {
                    observations.add(new ScoutingObservation(
                        "first_building", toObservationName(b.type()), frame));
                    firstBuildingEmitted = true;
                    break;
                }
            }
        }

        if (!gasTimingEmitted) {
            boolean hasGas = gs.enemyBuildings().stream()
                .anyMatch(b -> GAS_BUILDINGS.contains(b.type()));
            if (hasGas) {
                String timing = frame < GAS_EARLY ? "early"
                    : frame <= GAS_LATE ? "normal" : "late";
                observations.add(new ScoutingObservation("gas_timing", timing, frame));
                gasTimingEmitted = true;
            }
        }

        if (!expansionTimingEmitted) {
            long baseCount = gs.enemyBuildings().stream()
                .filter(b -> SC2Data.isBase(b.type())).count();
            if (baseCount >= 2) {
                String timing = frame < EXPAND_FAST ? "fast"
                    : frame <= EXPAND_LATE ? "normal" : "late";
                observations.add(new ScoutingObservation("expansion_timing", timing, frame));
                expansionTimingEmitted = true;
            }
        }

        if (!techPathEmitted) {
            for (Building b : gs.enemyBuildings()) {
                if (SC2Data.isTechBuilding(b.type())) {
                    observations.add(new ScoutingObservation(
                        "tech_path", toObservationName(b.type()), frame));
                    techPathEmitted = true;
                    break;
                }
            }
        }

        String armyComp = classifyArmyComposition(gs.enemyUnits());
        if (!armyComp.equals(lastArmyComposition)) {
            observations.add(new ScoutingObservation("army_composition", armyComp, frame));
            lastArmyComposition = armyComp;
        }

        String saturation = classifyWorkerSaturation(gs.enemyUnits(), gs.enemyBuildings());
        if (!saturation.equals(lastWorkerSaturation)) {
            observations.add(new ScoutingObservation("worker_saturation", saturation, frame));
            lastWorkerSaturation = saturation;
        }

        String aggression = classifyAggression(gs.enemyUnits(), frame);
        if (!aggression.equals(lastAggressionTiming)) {
            observations.add(new ScoutingObservation("aggression_timing", aggression, frame));
            lastAggressionTiming = aggression;
        }

        return List.copyOf(observations);
    }

    public void reset() {
        firstBuildingEmitted = false;
        gasTimingEmitted = false;
        expansionTimingEmitted = false;
        techPathEmitted = false;
        lastArmyComposition = null;
        lastWorkerSaturation = null;
        lastAggressionTiming = null;
    }

    static String toObservationName(BuildingType type) {
        return type.name().toLowerCase().replace("_", "");
    }

    static String classifyArmyComposition(List<Unit> enemyUnits) {
        int light = 0, armored = 0, air = 0;
        for (Unit u : enemyUnits) {
            if (SC2Data.isWorker(u.type())) continue;
            if (AIR_UNITS.contains(u.type())) air++;
            else if (LIGHT_UNITS.contains(u.type())) light++;
            else if (ARMORED_UNITS.contains(u.type())) armored++;
        }
        int total = light + armored + air;
        if (total == 0) return "none";
        if ((double) air / total > 0.5) return "air";
        if ((double) light / total > 0.6) return "light";
        if ((double) armored / total > 0.6) return "armored";
        return "mixed";
    }

    static String classifyWorkerSaturation(List<Unit> enemyUnits,
                                           List<Building> enemyBuildings) {
        int workers = 0;
        for (Unit u : enemyUnits) {
            if (SC2Data.isWorker(u.type())) workers++;
        }
        int bases = 0;
        for (Building b : enemyBuildings) {
            if (SC2Data.isBase(b.type())) bases++;
        }
        int effectiveBases = Math.max(1, bases);
        double perBase = (double) workers / effectiveBases;
        if (perBase < 16) return "under";
        if (perBase <= 22) return "saturated";
        return "over";
    }

    static String classifyAggression(List<Unit> enemyUnits, long frame) {
        boolean hasMilitary = enemyUnits.stream()
            .anyMatch(u -> !SC2Data.isWorker(u.type()));
        if (!hasMilitary) return "no_aggression";
        if (frame < AGGRESSION_EARLY) return "early_pressure";
        if (frame <= AGGRESSION_LATE) return "timing_attack";
        return "macro_play";
    }
}
