package io.quarkmind.domain;

public enum StrategyArchetype {
    // Early game — hand-authored DRL rules
    TERRAN_MARINE_RUSH(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.RUSH),
    TERRAN_BANSHEE_HARASS(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.HARASS),
    TERRAN_REAPER_HARASS(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.HARASS),
    TERRAN_PROXY_BARRACKS(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.RUSH),
    TERRAN_FAST_EXPAND(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.MACRO),
    ZERG_ZERGLING_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_ROACH_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_MACRO(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.MACRO),
    ZERG_BANELING_BUST(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_RAVAGER_PUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.TIMING),
    PROTOSS_GATEWAY_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_CANNON_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_MACRO(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.MACRO),
    PROTOSS_DT_HARASS(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.HARASS),
    PROTOSS_ORACLE_HARASS(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.HARASS),
    PROTOSS_ADEPT_HARASS(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.HARASS),
    PROTOSS_PROXY_GATE(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_DT_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),

    // Mid game — data-driven generic rules
    TERRAN_BIO_TIMING(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_MECH_PUSH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_AIR_SUPERIORITY(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_MARINE_TANK(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_BATTLE_MECH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_BIO_MINE(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_LIBERATOR_BIO(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_GHOST_BIO(Race.TERRAN, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    TERRAN_DROP_HARASS(Race.TERRAN, GamePhase.MID, ArchetypeCategory.HARASS),
    TERRAN_CYCLONE_PUSH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_MINE_DROP(Race.TERRAN, GamePhase.MID, ArchetypeCategory.HARASS),
    ZERG_ROACH_HYDRA(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_MUTALISK_HARASS(Race.ZERG, GamePhase.MID, ArchetypeCategory.HARASS),
    ZERG_HYDRA_PUSH(Race.ZERG, GamePhase.MID, ArchetypeCategory.TIMING),
    ZERG_LING_BANE(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_ROACH_RAVAGER(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_LURKER_CONTAIN(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_HYDRA_LURKER(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_SWARM_HOST(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    ZERG_LING_BANE_MUTA(Race.ZERG, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_STALKER_COLOSSUS(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_CHARGELOT_ARCHON(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_BLINK_STALKER(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.TIMING),
    PROTOSS_COLOSSUS_PUSH(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.TIMING),
    PROTOSS_AIR_SUPERIORITY(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_IMMORTAL_PUSH(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.TIMING),
    PROTOSS_PHOENIX_ADEPT(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_DISRUPTOR(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_STORM(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.COMPOSITION),
    PROTOSS_WARP_PRISM_HARASS(Race.PROTOSS, GamePhase.MID, ArchetypeCategory.HARASS),

    // Late game — data-driven generic rules
    TERRAN_BC_TRANSITION(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.TECH),
    TERRAN_GHOST_NUKE(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.TECH),
    TERRAN_MECH_LATE(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    TERRAN_BIO_LATE(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    TERRAN_SKY_TERRAN(Race.TERRAN, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    ZERG_BROOD_LORD(Race.ZERG, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    ZERG_ULTRALISK(Race.ZERG, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    ZERG_INFESTOR_BROOD(Race.ZERG, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    ZERG_VIPER_SUPPORT(Race.ZERG, GamePhase.LATE, ArchetypeCategory.TECH),
    ZERG_CORRUPTOR_MASS(Race.ZERG, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_CARRIER(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_TEMPEST_SIEGE(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_SKYTOSS(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_ARCHON_STORM(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.COMPOSITION),
    PROTOSS_MOTHERSHIP_FLEET(Race.PROTOSS, GamePhase.LATE, ArchetypeCategory.TECH);

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
