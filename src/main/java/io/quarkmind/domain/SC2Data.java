package io.quarkmind.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import static io.quarkmind.domain.UnitAttribute.*;

public final class SC2Data {

    private SC2Data() {}

    /** Game loops per outer tick at SC2 Faster speed. */
    public static final int    LOOPS_PER_TICK        = 22;

    /** Game loops per real-time second at SC2 Faster speed (16 loops/sec × 1.4 multiplier). */
    public static final double GAME_LOOPS_PER_SECOND = 22.4;

    /** Mineral patches in a standard base. Determines the width of each saturation tier. */
    public static final int MINERAL_PATCHES_PER_BASE = 8;

    /**
     * Per-probe mineral income per outer tick for each saturation tier.
     * Index = tier (0-based). Length = max effective workers per patch.
     * Swap or extend to adapt to a different RTS economy.
     */
    public static final double[] MINERAL_TIER_RATES_PER_TICK = {
        50.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // first worker per patch  (~0.818 min/tick)
        25.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // second worker per patch (~0.409 min/tick)
         5.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // third worker per patch  (~0.082 min/tick)
    };

    /**
     * Mineral income earned in one outer tick (LOOPS_PER_TICK game loops) for the given
     * probe count mining a single standard base (8 patches). Applies a three-tier saturation
     * model: first 8 probes earn full rate, next 8 earn half, next 8 earn ~10%. Beyond 24
     * probes there is no additional income.
     *
     * <p>Per-base income — callers with multiple bases sum this across each base's
     * probe count. See {@code EmulatedGame.tick()} for multi-base usage.
     *
     * @param probeCount number of mining probes (must be ≥ 0)
     * @throws IllegalArgumentException if probeCount is negative
     */
    public static double mineralIncomePerTick(final int probeCount) {
        if (probeCount < 0) throw new IllegalArgumentException("probeCount must be >= 0, got: " + probeCount);
        double income = 0;
        for (int tier = 0; tier < MINERAL_TIER_RATES_PER_TICK.length; tier++) {
            final int tierStart    = tier * MINERAL_PATCHES_PER_BASE;
            final int probesInTier = Math.min(Math.max(probeCount - tierStart, 0), MINERAL_PATCHES_PER_BASE);
            income += probesInTier * MINERAL_TIER_RATES_PER_TICK[tier];
        }
        return income;
    }

    public static final int INITIAL_MINERALS    = 50;
    public static final int INITIAL_VESPENE    = 0;
    public static final int INITIAL_SUPPLY     = 15;
    public static final int INITIAL_SUPPLY_USED = 12;
    public static final int INITIAL_PROBES     = 12;

    /**
     * MULE lifetime in game loops. 64s × 22.4 loops/s = 1433.6 → ceiling = 1434.
     * Uncalibrated — pending Terran replay data from #140.
     */
    public static final int MULE_LIFETIME_LOOPS = 1434;

    /**
     * Queen energy regeneration per game loop.
     * Standard SC2 spellcaster regen: 0.5625 energy/sec at Faster speed.
     * Per loop: 0.5625 / 22.4 ≈ 0.02511.
     */
    public static final double QUEEN_ENERGY_REGEN_PER_LOOP = 0.5625 / GAME_LOOPS_PER_SECOND;

    /**
     * Mineral income per active MULE per tick.
     * MULE mines at ~3.45× SCV rate. SCV earns ≈50 minerals/min → MULE ≈172.5/min.
     * Per tick (22 loops at 22.4 loops/sec = 0.982s): 172.5/60 × 0.982 ≈ 2.82.
     * Uncalibrated — pending Terran replay data from #140.
     */
    public static double muleIncomePerTick() {
        final double muleMinPerMin = 172.5;
        final double secsPerTick   = LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND;
        return muleMinPerMin / 60.0 * secsPerTick;
    }

    /**
     * Number of units spawned from a single TrainIntent.
     * Zergling produces 2 from 1 larva/1 supply cost; all others produce 1.
     */
    public static int trainCount(UnitType type) {
        return type == UnitType.ZERGLING ? 2 : 1;
    }

    /** Movement speed in tiles/sec at SC2 Faster speed (22.4 loops/sec). */
    public static final Map<UnitType, Double> UNIT_SPEEDS = Map.ofEntries(
        Map.entry(UnitType.PROBE,        3.94),
        Map.entry(UnitType.ZEALOT,       3.15),
        Map.entry(UnitType.STALKER,      4.13),
        Map.entry(UnitType.IMMORTAL,     3.15),
        Map.entry(UnitType.COLOSSUS,     2.77),
        Map.entry(UnitType.DISRUPTOR,    3.15),
        Map.entry(UnitType.ADEPT,        3.50),
        Map.entry(UnitType.ARCHON,       3.94),
        Map.entry(UnitType.PHOENIX,      5.61),
        Map.entry(UnitType.ORACLE,       5.61),
        Map.entry(UnitType.VOID_RAY,     3.50),
        Map.entry(UnitType.CARRIER,      1.97),
        Map.entry(UnitType.TEMPEST,      2.63),
        Map.entry(UnitType.MOTHERSHIP,   1.97),
        Map.entry(UnitType.OBSERVER,     2.63),
        Map.entry(UnitType.SCV,          3.94),
        Map.entry(UnitType.MARINE,       3.15),
        Map.entry(UnitType.MARAUDER,     2.25),
        Map.entry(UnitType.MEDIVAC,      3.50),
        Map.entry(UnitType.DRONE,        2.95),
        Map.entry(UnitType.ZERGLING,     4.13),
        Map.entry(UnitType.ROACH,        3.15),
        Map.entry(UnitType.HYDRALISK,    3.15),
        Map.entry(UnitType.MUTALISK,     5.61),
        Map.entry(UnitType.BANELING,     3.50),
        Map.entry(UnitType.ULTRALISK,    4.13),
        Map.entry(UnitType.OVERLORD,     1.40),
        Map.entry(UnitType.OVERSEER,     3.94)
    );

    public static final double DEFAULT_UNIT_SPEED = 3.00;

    public static double unitSpeed(UnitType type) {
        return UNIT_SPEEDS.getOrDefault(type, DEFAULT_UNIT_SPEED);
    }

    /**
     * Exact train time in game loops at SC2 Faster speed.
     * Source of truth for all train timing — {@link #trainTimeInTicks} derives from this.
     * Values are empirically calibrated from replay ground truth in
     * {@code SC2TrainTimeCalibrationTest} (paired GAME_EVENTS commands + tracker UnitBorn events,
     * 29 AI Arena replays). Observations: PROBE=499, ZEALOT=7, STALKER=2; IMMORTAL and OBSERVER
     * are uncalibrated (no observations in the Protoss-vs-Protoss bot corpus) and retain
     * integer-rounded estimates until replay data is available.
     *
     * <p>Note: values differ from {@code seconds × GAME_LOOPS_PER_SECOND} (22.4) because SC2
     * stores training times as integer game loops — see issue #149.
     */
    public static int trainTimeInLoops(UnitType type) {
        return switch (type) {
            // Protoss — empirical (SC2TrainTimeCalibrationTest, AI Arena replays)
            case PROBE    -> 272;  // empirical (499 obs)
            case ZEALOT   -> 618;  // empirical (7 obs)
            case STALKER  -> 698;  // empirical (2 obs — low confidence)
            case IMMORTAL -> 896;  // uncalibrated — 40s × 22.4 = 896.0
            case OBSERVER -> 493;  // uncalibrated — ceil(22s × 22.4)
            // Terran — uncalibrated estimates; pending Terran replays from #140
            case SCV      -> 275;  // estimate: ceil(12.25s × 22.4)
            case MARINE   -> 563;  // estimate: ceil(25.13s × 22.4)
            case MARAUDER -> 757;  // estimate: ceil(33.8s × 22.4)
            // Zerg — uncalibrated estimates; pending Zerg replay calibration
            case DRONE     -> 275; // estimate: same as SCV (same real-time duration)
            case ZERGLING  -> 400; // estimate: ceil(17.85s × 22.4)
            case ROACH     -> 572; // estimate: ceil(25.5s × 22.4)
            case HYDRALISK -> 672; // estimate: 30s × 22.4
            case OVERLORD  -> 357; // estimate: ceil(15.93s × 22.4)
            case QUEEN     -> 900; // estimate: ceil(40.18s × 22.4)
            default        -> 672; // uncalibrated — 30s × 22.4 = 672.0
        };
    }

    public static int trainTimeInTicks(UnitType type) {
        return trainTimeInLoops(type) / LOOPS_PER_TICK;
    }

    /**
     * Exact game-loop build time per building type.
     *
     * <p>Values are empirically calibrated from AI Arena replay tracker events
     * (UnitInit → UnitDone loop diff) in {@code SC2BuildTimeCalibrationTest} where
     * observations are available. Types marked "estimate" use {@code ticks × LOOPS_PER_TICK}
     * and should be replaced with calibrated values when replay data is available.
     *
     * <p><b>Addon contamination:</b> {@link io.quarkmind.sc2.mock.Sc2ReplayShared#toBuildingType}
     * maps add-on names (FactoryTechLab, BarracksTechLab, etc.) to their parent
     * {@link BuildingType}. {@code SC2BuildTimeCalibrationTest} filters these via
     * {@code ADDON_OR_MORPH_NAMES} before accumulating — values marked "addon-filtered"
     * are calibrated from clean structure-only completions. Filtered at the test layer since #154;
     * {@code toBuildingType} still maps addon names to parent types by design.
     */
    public static int buildTimeInLoops(BuildingType type) {
        return switch (type) {
            // Protoss — empirical (SC2BuildTimeCalibrationTest, AI Arena replays)
            case NEXUS             -> 1600; // empirical (71 obs, AI Arena replays)
            case PYLON             -> 400;  // empirical (446 obs, AI Arena replays)
            case GATEWAY           -> 1040; // empirical (189 obs, AI Arena replays)
            case CYBERNETICS_CORE  -> 800;  // empirical (38 obs, AI Arena replays)
            case ASSIMILATOR       -> 480;  // empirical (169 obs, AI Arena replays)
            case ROBOTICS_FACILITY -> 1040; // empirical (26 obs, AI Arena replays)
            case STARGATE          -> 960;  // empirical (31 obs, AI Arena replays)
            case FORGE             -> 720;  // empirical (31 obs, AI Arena replays)
            case TWILIGHT_COUNCIL  -> 800;  // empirical (21 obs, AI Arena replays)
            case PHOTON_CANNON     -> 640;  // empirical (114 obs, AI Arena replays)
            case SHIELD_BATTERY    -> 640;  // empirical (62 obs, AI Arena replays)
            case DARK_SHRINE       -> 1562; // estimate: 71 × 22
            case TEMPLAR_ARCHIVES  -> 814;  // estimate: 37 × 22
            case FLEET_BEACON      -> 960;  // empirical (11 obs, AI Arena replays)
            case ROBOTICS_BAY      -> 1040; // empirical (4 obs, AI Arena replays)
            // Terran — estimates (ticks × LOOPS_PER_TICK)
            case COMMAND_CENTER    -> 1600; // empirical (36 obs, AI Arena replays)
            case ORBITAL_COMMAND   -> 550;  // estimate: 25 × 22
            case PLANETARY_FORTRESS -> 660; // estimate: 30 × 22
            case SUPPLY_DEPOT      -> 480;  // empirical (177 obs, AI Arena replays)
            case BARRACKS          -> 1040; // empirical (25 obs, AI Arena replays, addon-filtered)
            case ENGINEERING_BAY   -> 560;  // empirical (12 obs, AI Arena replays)
            case ARMORY            -> 1040; // empirical (18 obs, AI Arena replays)
            case MISSILE_TURRET    -> 400;  // empirical (20 obs, AI Arena replays)
            case BUNKER            -> 640;  // empirical (29 obs, AI Arena replays)
            case SENSOR_TOWER      -> 352;  // estimate: 16 × 22
            case GHOST_ACADEMY     -> 550;  // estimate: 25 × 22
            case FACTORY           -> 960;  // empirical (16 obs, AI Arena replays, addon-filtered)
            case STARPORT          -> 800;  // empirical (39 obs, AI Arena replays, addon-filtered)
            case FUSION_CORE       -> 1040; // empirical (4 obs, AI Arena replays)
            case REFINERY          -> 480;  // empirical (80 obs, AI Arena replays)
            // Zerg — estimates (ticks × LOOPS_PER_TICK)
            case HATCHERY          -> 1600; // empirical (46 obs, AI Arena replays)
            case LAIR              -> 1254; // estimate: 57 × 22
            case HIVE              -> 1254; // estimate: 57 × 22
            case SPAWNING_POOL     -> 1040; // empirical (12 obs, AI Arena replays)
            case EVOLUTION_CHAMBER -> 560;  // empirical (17 obs, AI Arena replays)
            case ROACH_WARREN      -> 880;  // empirical (7 obs, AI Arena replays)
            case BANELING_NEST     -> 946;  // estimate: 43 × 22
            case SPINE_CRAWLER     -> 800;  // empirical (47 obs, AI Arena replays)
            case SPORE_CRAWLER     -> 480;  // empirical (15 obs, AI Arena replays)
            case HYDRALISK_DEN     -> 640;  // empirical (3 obs, AI Arena replays)
            case LURKER_DEN        -> 1254; // estimate: 57 × 22
            case INFESTATION_PIT   -> 800;  // empirical (6 obs, AI Arena replays)
            case SPIRE             -> 1600; // empirical (3 obs, AI Arena replays)
            case GREATER_SPIRE     -> 1562; // estimate: 71 × 22
            case NYDUS_NETWORK     -> 462;  // estimate: 21 × 22
            case NYDUS_CANAL       -> 242;  // estimate: 11 × 22
            case ULTRALISK_CAVERN  -> 1012; // estimate: 46 × 22
            case EXTRACTOR         -> 480;  // empirical (67 obs, AI Arena replays)
            default                -> 880;  // estimate: 40 × 22
        };
    }

    public static int buildTimeInTicks(BuildingType type) {
        return buildTimeInLoops(type) / LOOPS_PER_TICK;
    }

    private static final Map<UnitType, UnitCosts> UNIT_COSTS;
    static {
        var map = new EnumMap<UnitType, UnitCosts>(UnitType.class);
        // Protoss
        map.put(UnitType.PROBE,              new UnitCosts( 50,   0, 1));
        map.put(UnitType.ZEALOT,             new UnitCosts(100,   0, 2));
        map.put(UnitType.STALKER,            new UnitCosts(125,  50, 2));
        map.put(UnitType.IMMORTAL,           new UnitCosts(250, 100, 4));
        map.put(UnitType.COLOSSUS,           new UnitCosts(300, 200, 6));
        map.put(UnitType.CARRIER,            new UnitCosts(350, 250, 6));
        map.put(UnitType.DARK_TEMPLAR,       new UnitCosts(125, 125, 2));
        map.put(UnitType.HIGH_TEMPLAR,       new UnitCosts( 50, 150, 2));
        map.put(UnitType.ARCHON,             new UnitCosts(  0,   0, 0));
        map.put(UnitType.OBSERVER,           new UnitCosts( 25,  75, 1));
        map.put(UnitType.VOID_RAY,           new UnitCosts(250, 150, 4));
        map.put(UnitType.ADEPT,              new UnitCosts(100,  25, 2));
        map.put(UnitType.DISRUPTOR,          new UnitCosts(150, 150, 3));
        map.put(UnitType.SENTRY,             new UnitCosts( 50, 100, 2));
        map.put(UnitType.PHOENIX,            new UnitCosts(150, 100, 2));
        map.put(UnitType.ORACLE,             new UnitCosts(150, 150, 3));
        map.put(UnitType.TEMPEST,            new UnitCosts(250, 175, 4));
        map.put(UnitType.MOTHERSHIP,         new UnitCosts(400, 400, 8));
        map.put(UnitType.WARP_PRISM,         new UnitCosts(200,   0, 2));
        map.put(UnitType.WARP_PRISM_PHASING, new UnitCosts(200,   0, 2));
        map.put(UnitType.INTERCEPTOR,        new UnitCosts( 15,   0, 0));
        map.put(UnitType.ADEPT_PHASE_SHIFT,  new UnitCosts(  0,   0, 0));
        // Zerg
        map.put(UnitType.ZERGLING,           new UnitCosts( 25,   0, 1));
        map.put(UnitType.ROACH,              new UnitCosts( 75,  25, 2));
        map.put(UnitType.HYDRALISK,          new UnitCosts(100,  50, 2));
        map.put(UnitType.MUTALISK,           new UnitCosts(100, 100, 2));
        map.put(UnitType.ULTRALISK,          new UnitCosts(300, 200, 6));
        map.put(UnitType.BROOD_LORD,         new UnitCosts(150, 150, 2));
        map.put(UnitType.CORRUPTOR,          new UnitCosts(150, 100, 2));
        map.put(UnitType.INFESTOR,           new UnitCosts(100, 150, 2));
        map.put(UnitType.SWARM_HOST,         new UnitCosts(100,  75, 3));
        map.put(UnitType.VIPER,              new UnitCosts(100, 200, 3));
        map.put(UnitType.QUEEN,              new UnitCosts(150,   0, 2));
        map.put(UnitType.RAVAGER,            new UnitCosts( 25,  75, 1));
        map.put(UnitType.LURKER,             new UnitCosts( 50, 100, 1));
        map.put(UnitType.DRONE,              new UnitCosts( 50,   0, 1));
        map.put(UnitType.OVERLORD,           new UnitCosts(100,   0, 0));
        map.put(UnitType.OVERSEER,           new UnitCosts( 50,  50, 0));
        map.put(UnitType.BANELING,           new UnitCosts( 25,  25, 0));
        map.put(UnitType.LOCUST,             new UnitCosts(  0,   0, 0));
        map.put(UnitType.BROODLING,          new UnitCosts(  0,   0, 0));
        map.put(UnitType.INFESTED_TERRAN,    new UnitCosts(  0,   0, 0));
        map.put(UnitType.CHANGELING,         new UnitCosts(  0,   0, 0));
        map.put(UnitType.EGG,                new UnitCosts(  0,   0, 0));
        // Terran
        map.put(UnitType.MARINE,             new UnitCosts( 50,   0, 1));
        map.put(UnitType.MARAUDER,           new UnitCosts(100,  25, 2));
        map.put(UnitType.MEDIVAC,            new UnitCosts(100, 100, 2));
        map.put(UnitType.SIEGE_TANK,         new UnitCosts(150, 125, 3));
        map.put(UnitType.SIEGE_TANK_SIEGED,  new UnitCosts(150, 125, 3));
        map.put(UnitType.THOR,               new UnitCosts(300, 200, 6));
        map.put(UnitType.VIKING,             new UnitCosts(150,  75, 2));
        map.put(UnitType.GHOST,              new UnitCosts(150, 125, 2));
        map.put(UnitType.RAVEN,              new UnitCosts(100, 200, 2));
        map.put(UnitType.BANSHEE,            new UnitCosts(150, 100, 3));
        map.put(UnitType.BATTLECRUISER,      new UnitCosts(400, 300, 6));
        map.put(UnitType.CYCLONE,            new UnitCosts(150, 100, 3));
        map.put(UnitType.LIBERATOR,          new UnitCosts(150, 150, 3));
        map.put(UnitType.WIDOW_MINE,         new UnitCosts( 75,  25, 2));
        map.put(UnitType.SCV,                new UnitCosts( 50,   0, 1));
        map.put(UnitType.REAPER,             new UnitCosts( 50,  50, 1));
        map.put(UnitType.HELLION,            new UnitCosts(100,   0, 2));
        map.put(UnitType.HELLBAT,            new UnitCosts(100,   0, 2));
        map.put(UnitType.MULE,               new UnitCosts(  0,   0, 0));
        map.put(UnitType.VIKING_ASSAULT,     new UnitCosts(150,  75, 2));
        map.put(UnitType.LIBERATOR_AG,       new UnitCosts(150, 150, 3));
        map.put(UnitType.AUTO_TURRET,        new UnitCosts(  0,   0, 0));
        // Fallback
        map.put(UnitType.UNKNOWN,            new UnitCosts(  0,   0, 0));

        for (UnitType t : UnitType.values()) {
            if (!map.containsKey(t))
                throw new ExceptionInInitializerError("Missing UnitCosts for " + t);
        }
        UNIT_COSTS = Collections.unmodifiableMap(map);
    }

    public static UnitCosts unitCosts(UnitType type) { return UNIT_COSTS.get(type); }

    public static int supplyCost(UnitType type) { return UNIT_COSTS.get(type).supply(); }

    public static int supplyBonus(BuildingType type) {
        return switch (type) {
            case PYLON         -> 8;
            case SUPPLY_DEPOT  -> 8;
            case HATCHERY, LAIR, HIVE -> 6;
            default            -> 0;
        };
    }

    public static int maxHealth(UnitType type) {
        return switch (type) {
            // Protoss
            case PROBE     ->  45;
            case ZEALOT    -> 100;
            case STALKER   ->  80;
            case IMMORTAL  -> 200;
            // Terran
            case MARINE    ->  45;
            case MARAUDER  -> 125;
            case SCV       ->  45;
            case MULE      ->  60;
            // Zerg
            case ROACH     -> 145;
            case HYDRALISK ->  90;
            case DRONE     ->  40;
            case ZERGLING  ->  35;
            case OVERLORD  -> 200;
            case QUEEN     -> 175;
            case EGG       -> 200;
            default        -> 100;
        };
    }

    public static Set<UnitAttribute> unitAttributes(UnitType type) {
        return switch (type) {
            case PROBE     -> Set.of(LIGHT, MECHANICAL);
            case ZEALOT    -> Set.of(LIGHT, BIOLOGICAL);
            case STALKER   -> Set.of(ARMORED, MECHANICAL);
            case IMMORTAL  -> Set.of(ARMORED, MECHANICAL, MASSIVE);
            case OBSERVER  -> Set.of(ARMORED, MECHANICAL);
            case MARINE    -> Set.of(LIGHT, BIOLOGICAL);
            case MARAUDER  -> Set.of(BIOLOGICAL, ARMORED);
            case ROACH     -> Set.of(ARMORED, BIOLOGICAL);
            case HYDRALISK -> Set.of(LIGHT, BIOLOGICAL);
            default        -> Set.of();
        };
    }

    public static int armour(UnitType type) {
        return switch (type) {
            case ZEALOT, STALKER, IMMORTAL, MARAUDER, ROACH, SCV, QUEEN -> 1;
            default -> 0;
        };
    }

    public static int bonusDamageVs(UnitType attackerType, UnitAttribute targetAttribute) {
        return switch (attackerType) {
            case STALKER  -> targetAttribute == ARMORED ? 4  : 0;
            case IMMORTAL -> targetAttribute == ARMORED ? 3  : 0;
            case MARAUDER -> targetAttribute == ARMORED ? 10 : 0;
            default       -> 0;
        };
    }

    public static boolean hasHardenedShield(UnitType type) {
        return type == UnitType.IMMORTAL;
    }

    public static int maxShields(UnitType type) {
        return switch (type) {
            case PROBE    -> 20;
            case ZEALOT   -> 50;
            case STALKER  -> 80;
            case IMMORTAL -> 100;
            case OBSERVER -> 20;
            case VOID_RAY -> 100;
            default       -> 0;   // Terran/Zerg have no shields
        };
    }

    public static int maxBuildingHealth(BuildingType type) {
        return switch (type) {
            // Protoss
            case NEXUS              -> 1500;
            case PYLON              -> 200;
            case GATEWAY            -> 500;
            case CYBERNETICS_CORE   -> 500;
            case ASSIMILATOR        -> 450;
            case ROBOTICS_FACILITY  -> 500;
            case STARGATE           -> 600;
            case FORGE              -> 550;
            case TWILIGHT_COUNCIL   -> 500;
            case PHOTON_CANNON      -> 150;
            case SHIELD_BATTERY     -> 200;
            case DARK_SHRINE        -> 500;
            case TEMPLAR_ARCHIVES   -> 500;
            case FLEET_BEACON       -> 500;
            case ROBOTICS_BAY       -> 500;
            // Terran
            case COMMAND_CENTER     -> 1500;
            case ORBITAL_COMMAND    -> 1500;
            case PLANETARY_FORTRESS -> 1500;
            case SUPPLY_DEPOT       -> 400;
            case BARRACKS           -> 1000;
            case ENGINEERING_BAY    -> 850;
            case ARMORY             -> 750;
            case MISSILE_TURRET     -> 250;
            case BUNKER             -> 400;
            case SENSOR_TOWER       -> 200;
            case GHOST_ACADEMY      -> 1250;
            case FACTORY            -> 1250;
            case STARPORT           -> 1000;
            case FUSION_CORE        -> 750;
            case REFINERY           -> 500;
            // Zerg
            case HATCHERY           -> 1500;
            case LAIR               -> 2000;
            case HIVE               -> 2500;
            case SPAWNING_POOL      -> 750;
            case EVOLUTION_CHAMBER  -> 750;
            case ROACH_WARREN       -> 850;
            case BANELING_NEST      -> 850;
            case SPINE_CRAWLER      -> 300;
            case SPORE_CRAWLER      -> 400;
            case HYDRALISK_DEN      -> 850;
            case LURKER_DEN         -> 850;
            case INFESTATION_PIT    -> 850;
            case SPIRE              -> 850;
            case GREATER_SPIRE      -> 1000;
            case NYDUS_NETWORK      -> 850;
            case NYDUS_CANAL        -> 250;
            case ULTRALISK_CAVERN   -> 850;
            case EXTRACTOR          -> 500;
            default                 -> 500;
        };
    }

    public static int mineralCost(UnitType type) { return UNIT_COSTS.get(type).mineral(); }

    public static int mineralCost(BuildingType type) {
        return switch (type) {
            // Protoss
            case NEXUS              -> 400;
            case PYLON              -> 100;
            case GATEWAY            -> 150;
            case CYBERNETICS_CORE   -> 150;
            case ASSIMILATOR        -> 75;
            case ROBOTICS_FACILITY  -> 200;
            case STARGATE           -> 150;
            case FORGE              -> 150;
            case TWILIGHT_COUNCIL   -> 150;
            case PHOTON_CANNON      -> 150;
            case SHIELD_BATTERY     -> 100;
            case DARK_SHRINE        -> 150;
            case TEMPLAR_ARCHIVES   -> 150;
            case FLEET_BEACON       -> 300;
            case ROBOTICS_BAY       -> 200;
            // Terran
            case COMMAND_CENTER     -> 400;
            case ORBITAL_COMMAND    -> 150;
            case PLANETARY_FORTRESS -> 150;
            case SUPPLY_DEPOT       -> 100;
            case BARRACKS           -> 150;
            case ENGINEERING_BAY    -> 125;
            case ARMORY             -> 150;
            case MISSILE_TURRET     -> 100;
            case BUNKER             -> 100;
            case SENSOR_TOWER       -> 125;
            case GHOST_ACADEMY      -> 150;
            case FACTORY            -> 150;
            case STARPORT           -> 150;
            case FUSION_CORE        -> 150;
            case REFINERY           -> 75;
            // Zerg
            case HATCHERY           -> 300;
            case LAIR               -> 150;
            case HIVE               -> 200;
            case SPAWNING_POOL      -> 200;
            case EVOLUTION_CHAMBER  -> 75;
            case ROACH_WARREN       -> 150;
            case BANELING_NEST      -> 100;
            case SPINE_CRAWLER      -> 100;
            case SPORE_CRAWLER      -> 75;
            case HYDRALISK_DEN      -> 100;
            case LURKER_DEN         -> 150;
            case INFESTATION_PIT    -> 100;
            case SPIRE              -> 200;
            case GREATER_SPIRE      -> 100;
            case NYDUS_NETWORK      -> 150;
            case NYDUS_CANAL        -> 50;
            case ULTRALISK_CAVERN   -> 150;
            case EXTRACTOR          -> 25;
            default                 -> 100;
        };
    }

    public static int gasCost(UnitType type) { return UNIT_COSTS.get(type).gas(); }

    public static BuildingType trainedBy(UnitType type) {
        return switch (type) {
            case PROBE                                  -> BuildingType.NEXUS;
            case ZEALOT, STALKER, ADEPT, ARCHON        -> BuildingType.GATEWAY;
            case IMMORTAL, OBSERVER, COLOSSUS,
                 DISRUPTOR                             -> BuildingType.ROBOTICS_FACILITY;
            case PHOENIX, ORACLE, VOID_RAY,
                 CARRIER, TEMPEST, MOTHERSHIP          -> BuildingType.STARGATE;
            case SCV                                   -> BuildingType.COMMAND_CENTER;
            case MULE                                  -> BuildingType.ORBITAL_COMMAND;
            case MARINE, MARAUDER                      -> BuildingType.BARRACKS;
            case MEDIVAC, VIKING                       -> BuildingType.STARPORT;
            case HELLION                               -> BuildingType.FACTORY;
            case DRONE, ZERGLING, ROACH, HYDRALISK,
                 MUTALISK, BANELING, ULTRALISK,
                 OVERLORD, OVERSEER, QUEEN             -> BuildingType.HATCHERY;
            default                                    -> BuildingType.UNKNOWN;
        };
    }

    /**
     * Damage dealt per attack event (replaces damagePerTick from E3).
     * Phase E4: units fire at cooldown intervals, not every tick.
     *
     * <p><b>Note: these are simplified/balanced values, not exact SC2 numbers.</b>
     * Real SC2 stats (e.g. Immortal: 50+100 vs Armored, Stalker: 13+5 vs Armored)
     * are scaled down to keep simulation legible at 500ms/tick. Values are tuned
     * for realistic fight outcomes, not stat-sheet accuracy.
     */
    public static int damagePerAttack(UnitType type) {
        return switch (type) {
            case PROBE     ->  5;
            case ZEALOT    ->  8;
            case STALKER   -> 13;
            case IMMORTAL  -> 20;
            case MARINE    ->  6;
            case MARAUDER  -> 10;
            case SCV       ->  5;
            case ROACH     ->  9;
            case HYDRALISK -> 12;
            case ZERGLING  ->  5;
            case QUEEN     -> 20;
            case MULE      ->  0;
            case EGG       ->  0;
            default        ->  5;
        };
    }

    /** Ticks between attacks (cooldown reset after firing). 1 tick = 500ms at Faster speed. */
    public static int attackCooldownInTicks(UnitType type) {
        return switch (type) {
            case MARINE, HYDRALISK, STALKER, ZERGLING -> 1;
            case PROBE, ZEALOT, IMMORTAL, MARAUDER, ROACH, SCV, QUEEN -> 2;
            case MULE, EGG -> Integer.MAX_VALUE; // non-combat
            default        -> 2;
        };
    }

    /** Attack range in tiles. Zealots are melee (0.5 tiles). */
    public static float attackRange(UnitType type) {
        return switch (type) {
            case ZEALOT, SCV, ZERGLING -> 0.5f;
            case PROBE                 -> 3.0f;
            case STALKER, MARINE, MARAUDER, HYDRALISK -> 5.0f;
            case IMMORTAL              -> 5.5f;
            case ROACH                 -> 4.0f;
            case QUEEN                 -> 5.0f;
            case MULE, EGG             -> 0.0f; // non-combat
            default                    -> 3.0f;
        };
    }

    /** Official SC2 sight radius in tiles. Friendly units only — we compute friendly vision. */
    public static int sightRange(UnitType type) {
        return switch (type) {
            case PROBE   -> 8;
            case ZEALOT  -> 9;
            case STALKER -> 10;
            default      -> 9;
        };
    }

    /** Official SC2 sight radius in tiles for buildings. */
    public static int sightRange(BuildingType type) {
        return switch (type) {
            case ASSIMILATOR -> 6;
            default          -> 9;  // NEXUS, GATEWAY, FORGE, etc.
        };
    }

    /** Blink range in tiles (STALKER only). */
    public static float blinkRange(UnitType type) {
        return type == UnitType.STALKER ? 8.0f : 0.0f;
    }

    /** Ticks before blink can be used again. 21 ticks ≈ 10.5s at 500ms/tick. */
    public static int blinkCooldownInTicks(UnitType type) {
        return type == UnitType.STALKER ? 21 : 0;
    }

    /** Shields restored on blink (capped at maxShields at call site). */
    public static int blinkShieldRestore(UnitType type) {
        return type == UnitType.STALKER ? 40 : 0;
    }

    /**
     * Circular collision radius (in tiles) used by the emulated physics engine.
     * Units cannot enter a completed building's radius — they are blocked and repath.
     * Radii approximate real SC2 building footprints: 5×5 → 2.5, 3×3 → 1.5, 2×2 → 1.0.
     */
    public static float buildingRadius(BuildingType type) {
        return switch (type) {
            // Large (5×5 footprint)
            case NEXUS, HATCHERY, LAIR, HIVE,
                 COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS -> 2.5f;
            // Large-medium (4×4)
            case ULTRALISK_CAVERN -> 2.0f;
            // Small (2×2 footprint) — turrets, extractors, crawlers, power structures
            case PYLON, SUPPLY_DEPOT, MISSILE_TURRET, SENSOR_TOWER,
                 PHOTON_CANNON, SHIELD_BATTERY,
                 SPINE_CRAWLER, SPORE_CRAWLER,
                 EVOLUTION_CHAMBER, ASSIMILATOR, REFINERY, EXTRACTOR,
                 NYDUS_CANAL -> 1.0f;
            // Medium (3×3 or 4×3) — all remaining tech buildings
            default -> 1.5f;
        };
    }

    /**
     * Tech tier per building type (1-4). Empty for non-tech buildings (bases, supply, defence, gas).
     */
    public static OptionalInt techTier(BuildingType type) {
        return switch (type) {
            // Protoss T1
            case GATEWAY -> OptionalInt.of(1);
            // Protoss T2
            case ROBOTICS_FACILITY, STARGATE -> OptionalInt.of(2);
            // Protoss T3
            case TWILIGHT_COUNCIL, TEMPLAR_ARCHIVES, DARK_SHRINE, FORGE -> OptionalInt.of(3);
            // Protoss T4
            case FLEET_BEACON, ROBOTICS_BAY -> OptionalInt.of(4);
            // Terran T1
            case BARRACKS -> OptionalInt.of(1);
            case ENGINEERING_BAY -> OptionalInt.of(1);
            // Terran T2
            case FACTORY, STARPORT -> OptionalInt.of(2);
            // Terran T3
            case GHOST_ACADEMY, ARMORY -> OptionalInt.of(3);
            // Terran T4
            case FUSION_CORE -> OptionalInt.of(4);
            // Zerg T1
            case SPAWNING_POOL, EVOLUTION_CHAMBER -> OptionalInt.of(1);
            // Zerg T2
            case ROACH_WARREN, BANELING_NEST, HYDRALISK_DEN, SPIRE -> OptionalInt.of(2);
            // Zerg T3
            case INFESTATION_PIT, LURKER_DEN, NYDUS_NETWORK -> OptionalInt.of(3);
            // Zerg T4
            case GREATER_SPIRE, ULTRALISK_CAVERN -> OptionalInt.of(4);
            // Non-tech: bases, supply, defence, gas, unknown
            default -> OptionalInt.empty();
        };
    }

    /**
     * True if the unit is a worker (PROBE, SCV, DRONE).
     */
    public static boolean isWorker(UnitType type) {
        return type == UnitType.PROBE || type == UnitType.SCV || type == UnitType.DRONE;
    }

    /**
     * True if the building is a base (NEXUS, COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS,
     * HATCHERY, LAIR, HIVE).
     */
    public static boolean isBase(BuildingType type) {
        return switch (type) {
            case NEXUS, COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS,
                 HATCHERY, LAIR, HIVE -> true;
            default -> false;
        };
    }
}
