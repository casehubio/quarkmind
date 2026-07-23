package io.quarkmind.domain;

public enum StrategyArchetype {
    // Early game — hand-authored DRL rules
    TERRAN_MARINE_RUSH(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.RUSH),
    TERRAN_BANSHEE_HARASS(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.HARASS),
    ZERG_ZERGLING_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_ROACH_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_MACRO(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.MACRO),
    PROTOSS_GATEWAY_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_CANNON_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_MACRO(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.MACRO),
    PROTOSS_DT_HARASS(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.HARASS),

    // Mid game — data-driven generic rules
    TERRAN_BIO_TIMING(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_MECH_PUSH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_MARINE_TANK(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_BATTLE_MECH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_ROACH_HYDRA(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_MUTALISK_HARASS(Race.ZERG, GamePhase.MID, ArchetypeCategory.HARASS),
    PROTOSS_STALKER_COLOSSUS(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_CHARGELOT_ARCHON(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),

    // Late game — data-driven generic rules
    TERRAN_BC_TRANSITION(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.TECH),
    ZERG_BROOD_LORD(Race.ZERG, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_CARRIER(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.COMPOSITION);

    private final Race              race;
    private final GamePhase         phase;
    private final ArchetypeCategory category;

    StrategyArchetype(Race race, GamePhase phase, ArchetypeCategory category) {
        this.race     = race;
        this.phase    = phase;
        this.category = category;
    }

    public Race race()                  {return race;}

    public GamePhase phase()            {return phase;}

    public ArchetypeCategory category() {return category;}
}
