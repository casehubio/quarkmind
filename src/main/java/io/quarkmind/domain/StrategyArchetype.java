package io.quarkmind.domain;

public enum StrategyArchetype {
    TERRAN_MARINE_RUSH(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.RUSH),
    TERRAN_BIO_TIMING(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_MECH_PUSH(Race.TERRAN, GamePhase.MID, ArchetypeCategory.TIMING),
    TERRAN_BANSHEE_HARASS(Race.TERRAN, GamePhase.EARLY, ArchetypeCategory.HARASS),
    ZERG_ZERGLING_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_ROACH_RUSH(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.RUSH),
    ZERG_MACRO(Race.ZERG, GamePhase.EARLY, ArchetypeCategory.MACRO),
    PROTOSS_GATEWAY_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_CANNON_RUSH(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.RUSH),
    PROTOSS_MACRO(Race.PROTOSS, GamePhase.EARLY, ArchetypeCategory.MACRO);

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
