package io.quarkmind.domain;

public enum EnemyArchetype {
    TERRAN_MARINE_RUSH(Race.TERRAN),
    TERRAN_BIO_TIMING(Race.TERRAN),
    TERRAN_MECH_PUSH(Race.TERRAN),
    TERRAN_BANSHEE_HARASS(Race.TERRAN),
    ZERG_ZERGLING_RUSH(Race.ZERG),
    ZERG_ROACH_RUSH(Race.ZERG),
    ZERG_MACRO(Race.ZERG),
    PROTOSS_GATEWAY_RUSH(Race.PROTOSS),
    PROTOSS_CANNON_RUSH(Race.PROTOSS),
    PROTOSS_MACRO(Race.PROTOSS);

    private final Race race;

    EnemyArchetype(Race race) {this.race = race;}

    public Race race()        {return race;}
}
