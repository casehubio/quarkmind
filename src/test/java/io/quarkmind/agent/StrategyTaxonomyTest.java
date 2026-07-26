package io.quarkmind.agent;

import io.quarkmind.domain.ArchetypeCategory;
import io.quarkmind.domain.CounterInfo;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyTaxonomyTest {

    private static StrategyTaxonomy taxonomy;

    @BeforeAll
    static void loadTaxonomy() {
        taxonomy = new StrategyTaxonomy();
        taxonomy.init();
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasYamlEntry(StrategyArchetype arch) {
        assertThat(taxonomy.lookup(arch)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_yamlMatchesEnumFields(StrategyArchetype arch) {
        var entry = taxonomy.lookup(arch);
        assertThat(entry.race()).isEqualTo(arch.race());
        assertThat(entry.phase()).isEqualTo(arch.phase());
        assertThat(entry.category()).isEqualTo(arch.category());
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasDisplayName(StrategyArchetype arch) {
        assertThat(taxonomy.lookup(arch).displayName()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasStrongCounters(StrategyArchetype arch) {
        CounterInfo counters = taxonomy.countersFor(arch);
        assertThat(counters).isNotNull();
        assertThat(counters.strongCounters()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasDetectionSignals(StrategyArchetype arch) {
        assertThat(taxonomy.lookup(arch).detectionSignals()).isNotEmpty();
    }

    @Test
    void forPhase_early_returnsOnlyEarlyArchetypes() {
        var earlyEntries = taxonomy.forPhase(GamePhase.EARLY);
        assertThat(earlyEntries).isNotEmpty();
        assertThat(earlyEntries).allSatisfy(e -> assertThat(e.phase()).isEqualTo(GamePhase.EARLY));
    }

    @Test
    void lookup_marineRush_hasCorrectFields() {
        var entry = taxonomy.lookup(StrategyArchetype.TERRAN_MARINE_RUSH);
        assertThat(entry.displayName()).isEqualTo("Marine Rush");
        assertThat(entry.race()).isEqualTo(Race.TERRAN);
        assertThat(entry.phase()).isEqualTo(GamePhase.EARLY);
        assertThat(entry.category()).isEqualTo(ArchetypeCategory.RUSH);
        assertThat(entry.handAuthored()).isTrue();
        assertThat(entry.phaseWindow()).containsExactly(0.0, 5.0);
    }

    @Test
    void countersFor_marineRush_hasProtossCounters() {
        var counters = taxonomy.countersFor(StrategyArchetype.TERRAN_MARINE_RUSH);
        assertThat(counters.strongCounters()).isNotEmpty();
        assertThat(counters.strongCounters().get(0).action()).contains("Stalker");
    }

    @Test
    void activeSignatures_allHandAuthored_returnsEmpty() {
        assertThat(taxonomy.activeSignatures(2.0)).isEmpty();
        assertThat(taxonomy.activeSignatures(7.0)).isEmpty();
        assertThat(taxonomy.activeSignatures(15.0)).isEmpty();
    }

}
