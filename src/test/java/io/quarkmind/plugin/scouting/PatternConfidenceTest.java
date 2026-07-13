package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.EnemyArchetype;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PatternConfidenceTest {

    @Test
    void singleWeight_returnsWeight() {
        var markers = List.of(new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.5, "test"));
        double conf = PatternClassifier.computeTickConfidence(markers);
        assertThat(conf).isCloseTo(0.5, within(0.001));
    }

    @Test
    void twoWeights_probabilityFormula() {
        var markers = List.of(
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.5, "a"),
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.5, "b"));
        double conf = PatternClassifier.computeTickConfidence(markers);
        assertThat(conf).isCloseTo(0.75, within(0.001));
    }

    @Test
    void emptyMarkers_returnsZero() {
        double conf = PatternClassifier.computeTickConfidence(List.of());
        assertThat(conf).isEqualTo(0.0);
    }

    @Test
    void threeWeights_compoundProbability() {
        var markers = List.of(
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3, "a"),
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3, "b"),
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3, "c"));
        double conf = PatternClassifier.computeTickConfidence(markers);
        assertThat(conf).isCloseTo(1.0 - 0.7 * 0.7 * 0.7, within(0.001));
    }

    @Test
    void computeAllConfidences_groupsByArchetype() {
        var markers = List.of(
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.5, "marines"),
            new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3, "no expansion"),
            new EvidenceMarker(EnemyArchetype.TERRAN_BIO_TIMING, 0.4, "medivac"));
        var confidences = PatternClassifier.computeAllConfidences(markers);

        assertThat(confidences).containsKeys(EnemyArchetype.TERRAN_MARINE_RUSH, EnemyArchetype.TERRAN_BIO_TIMING);
        assertThat(confidences.get(EnemyArchetype.TERRAN_MARINE_RUSH)).isCloseTo(0.65, within(0.001));
        assertThat(confidences.get(EnemyArchetype.TERRAN_BIO_TIMING)).isCloseTo(0.4, within(0.001));
    }

    @Test
    void cumulativeMerge_takesMax() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        cumulative.put(EnemyArchetype.TERRAN_MARINE_RUSH, 0.4);

        PatternClassifier.mergeCumulative(cumulative, Map.of(EnemyArchetype.TERRAN_MARINE_RUSH, 0.6));

        assertThat(cumulative.get(EnemyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.6);
    }

    @Test
    void cumulativeMerge_doesNotDecrease() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        cumulative.put(EnemyArchetype.TERRAN_MARINE_RUSH, 0.8);

        PatternClassifier.mergeCumulative(cumulative, Map.of(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3));

        assertThat(cumulative.get(EnemyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.8);
    }

    @Test
    void cumulativeMerge_addsNewArchetypes() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        cumulative.put(EnemyArchetype.TERRAN_MARINE_RUSH, 0.5);

        PatternClassifier.mergeCumulative(cumulative, Map.of(EnemyArchetype.ZERG_ROACH_RUSH, 0.7));

        assertThat(cumulative).containsKeys(EnemyArchetype.TERRAN_MARINE_RUSH, EnemyArchetype.ZERG_ROACH_RUSH);
    }

    @Test
    void topAssessment_returnsHighestConfidence() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        cumulative.put(EnemyArchetype.TERRAN_MARINE_RUSH, 0.7);
        cumulative.put(EnemyArchetype.TERRAN_BIO_TIMING, 0.3);

        var top = PatternClassifier.topAssessment(cumulative, 100L);
        assertThat(top).isPresent();
        assertThat(top.get().archetype()).isEqualTo(EnemyArchetype.TERRAN_MARINE_RUSH);
        assertThat(top.get().confidence()).isEqualTo(0.7);
    }

    @Test
    void topAssessment_belowThreshold_returnsEmpty() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        cumulative.put(EnemyArchetype.TERRAN_MARINE_RUSH, 0.2);

        var top = PatternClassifier.topAssessment(cumulative, 100L);
        assertThat(top).isEmpty();
    }

    @Test
    void topAssessment_emptyMap_returnsEmpty() {
        var cumulative = new EnumMap<EnemyArchetype, Double>(EnemyArchetype.class);
        var top = PatternClassifier.topAssessment(cumulative, 100L);
        assertThat(top).isEmpty();
    }
}
