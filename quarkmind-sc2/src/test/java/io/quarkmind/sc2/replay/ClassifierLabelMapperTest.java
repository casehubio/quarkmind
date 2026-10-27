package io.quarkmind.sc2.replay;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierLabelMapperTest {

    @Test
    void mapRush_vsTerran_early() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("RUSH", "vs_terran", GamePhase.EARLY);
        assertTrue(archetypes.contains(StrategyArchetype.TERRAN_MARINE_RUSH));
        assertTrue(archetypes.contains(StrategyArchetype.TERRAN_PROXY_BARRACKS));
        assertFalse(archetypes.contains(StrategyArchetype.ZERG_ZERGLING_RUSH));
    }

    @Test
    void mapMacroEconomy_vsZerg_early() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("MACRO_ECONOMY", "vs_zerg", GamePhase.EARLY);
        assertTrue(archetypes.contains(StrategyArchetype.ZERG_MACRO));
    }

    @Test
    void mapCannonRush_vsProtoss() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("CANNON_RUSH", "vs_protoss", GamePhase.EARLY);
        assertTrue(archetypes.contains(StrategyArchetype.PROTOSS_CANNON_RUSH));
        assertEquals(1, archetypes.size());
    }

    @Test
    void mapDtRush_vsProtoss() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("DT_RUSH", "vs_protoss", GamePhase.EARLY);
        assertTrue(archetypes.contains(StrategyArchetype.PROTOSS_DT_RUSH));
    }

    @Test
    void mapBioTiming_vsTerran_mid() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("BIO_TIMING", "vs_terran", GamePhase.MID);
        assertTrue(archetypes.contains(StrategyArchetype.TERRAN_BIO_TIMING));
    }

    @Test
    void mapTechRush_vsZerg_early() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("TECH_RUSH", "vs_zerg", GamePhase.EARLY);
        assertTrue(archetypes.contains(StrategyArchetype.ZERG_TECH_RUSH));
    }

    @Test
    void matchupToOpponentRace() {
        assertEquals(Race.TERRAN, ClassifierLabelMapper.opponentRaceEnum("vs_terran"));
        assertEquals(Race.ZERG, ClassifierLabelMapper.opponentRaceEnum("vs_zerg"));
        assertEquals(Race.PROTOSS, ClassifierLabelMapper.opponentRaceEnum("vs_protoss"));
    }

    @Test
    void excludesUnknownArchetypes() {
        var archetypes = ClassifierLabelMapper.mapToArchetypes("RUSH", "vs_terran", GamePhase.MID);
        for (var arch : archetypes) {
            assertFalse(arch.name().contains("UNKNOWN"));
        }
    }

    @Test
    void archLabelForArchetype() {
        assertEquals("MARINE_RUSH", ClassifierLabelMapper.archLabel(StrategyArchetype.TERRAN_MARINE_RUSH));
        assertEquals("MACRO", ClassifierLabelMapper.archLabel(StrategyArchetype.ZERG_MACRO));
        assertEquals("CANNON_RUSH", ClassifierLabelMapper.archLabel(StrategyArchetype.PROTOSS_CANNON_RUSH));
    }
}
