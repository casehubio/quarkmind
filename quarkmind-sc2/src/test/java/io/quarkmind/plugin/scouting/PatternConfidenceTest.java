package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PatternConfidenceTest {

    @Test
    void singleWeight_returnsWeight() {
        var markers = List.of(new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, "test"));
        double conf = PatternClassifier.computeTickConfidence(markers);
        assertThat(conf).isCloseTo(0.5, within(0.001));
    }

    @Test
    void twoWeights_probabilityFormula() {
        var markers = List.of(
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, "a"),
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, "b"));
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
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3, "a"),
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3, "b"),
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3, "c"));
        double conf = PatternClassifier.computeTickConfidence(markers);
        assertThat(conf).isCloseTo(1.0 - 0.7 * 0.7 * 0.7, within(0.001));
    }

    @Test
    void computeAllConfidences_groupsByArchetype() {
        var markers = List.of(
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, "marines"),
            new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3, "no expansion"),
            new EvidenceMarker(StrategyArchetype.TERRAN_BIO_TIMING, 0.4, "medivac"));
        var confidences = PatternClassifier.computeAllConfidences(markers);

        assertThat(confidences).containsKeys(StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.TERRAN_BIO_TIMING);
        assertThat(confidences.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isCloseTo(0.65, within(0.001));
        assertThat(confidences.get(StrategyArchetype.TERRAN_BIO_TIMING)).isCloseTo(0.4, within(0.001));
    }

    @Test
    void cumulativeMerge_takesMax() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.4);

        PatternClassifier.mergeCumulative(cumulative, Map.of(StrategyArchetype.TERRAN_MARINE_RUSH, 0.6), 100, 100);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.6);
    }

    @Test
    void cumulativeMerge_doesNotDecrease_withinSameFrame() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8);

        PatternClassifier.mergeCumulative(cumulative, Map.of(StrategyArchetype.TERRAN_MARINE_RUSH, 0.3), 100, 100);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.8);
    }

    @Test
    void cumulativeMerge_addsNewArchetypes() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5);

        PatternClassifier.mergeCumulative(cumulative, Map.of(StrategyArchetype.ZERG_ROACH_RUSH, 0.7), 100, 100);

        assertThat(cumulative).containsKeys(StrategyArchetype.TERRAN_MARINE_RUSH, StrategyArchetype.ZERG_ROACH_RUSH);
    }

    @Test
    void decay_reducesConfidenceOverFrames() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8);

        PatternClassifier.mergeCumulative(cumulative, Map.of(), 1344, 0);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH))
                .isCloseTo(0.4, within(0.02));
    }

    @Test
    void decay_removesEntriesBelowNoiseFloor() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.02);

        PatternClassifier.mergeCumulative(cumulative, Map.of(), 2000, 0);

        assertThat(cumulative).doesNotContainKey(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void decay_skippedOnFirstInvocation() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8);

        PatternClassifier.mergeCumulative(cumulative, Map.of(), 100, -1);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.8);
    }

    @Test
    void decay_steadyEvidenceProducesStableConfidence() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.65);

        PatternClassifier.mergeCumulative(cumulative,
                                          Map.of(StrategyArchetype.TERRAN_MARINE_RUSH, 0.65), 200, 100);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.65);
    }

    @Test
    void applyRevisions_dampensConfidence() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8);

        var revisions = List.of(new ConfidenceRevision(
                StrategyArchetype.ZERG_ZERGLING_RUSH, 0.997, "expansion detected"));
        PatternClassifier.applyRevisions(cumulative, revisions, 1344);

        assertThat(cumulative.get(StrategyArchetype.ZERG_ZERGLING_RUSH))
                .isLessThan(0.5);
    }

    @Test
    void applyRevisions_multipleRevisionsCompound() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8);

        var revisions = List.of(
                new ConfidenceRevision(StrategyArchetype.TERRAN_MARINE_RUSH, 0.997, "expansion"),
                new ConfidenceRevision(StrategyArchetype.TERRAN_MARINE_RUSH, 0.998, "tech"));
        PatternClassifier.applyRevisions(cumulative, revisions, 100);

        double singleFactor = Math.pow(0.997, 100) * Math.pow(0.998, 100);
        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH))
                .isCloseTo(0.8 * singleFactor, within(0.001));
    }

    @Test
    void applyRevisions_clampsToZero() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.01);

        var revisions = List.of(new ConfidenceRevision(
                StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "strong counter"));
        PatternClassifier.applyRevisions(cumulative, revisions, 100);

        assertThat(cumulative.get(StrategyArchetype.ZERG_ZERGLING_RUSH))
                .isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void applyRevisions_emptyRevisions_noChange() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.7);

        PatternClassifier.applyRevisions(cumulative, List.of(), 100);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.7);
    }

    @Test
    void applyRevisions_ignoresUnmatchedArchetypes() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.7);

        var revisions = List.of(new ConfidenceRevision(
                StrategyArchetype.ZERG_ZERGLING_RUSH, 0.997, "expansion"));
        PatternClassifier.applyRevisions(cumulative, revisions, 100);

        assertThat(cumulative.get(StrategyArchetype.TERRAN_MARINE_RUSH)).isEqualTo(0.7);
    }

    @Test
    void allAssessments_returnsAllAboveThreshold() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.7);
        cumulative.put(StrategyArchetype.TERRAN_BIO_TIMING, 0.4);
        cumulative.put(StrategyArchetype.ZERG_MACRO, 0.1);

        var assessments = PatternClassifier.allAssessments(cumulative, 100L);

        assertThat(assessments).hasSize(2);
        assertThat(assessments.get(0).archetype()).isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
        assertThat(assessments.get(1).archetype()).isEqualTo(StrategyArchetype.TERRAN_BIO_TIMING);
    }

    @Test
    void allAssessments_sortedByConfidenceDescending() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_BIO_TIMING, 0.5);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8);
        cumulative.put(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.6);

        var assessments = PatternClassifier.allAssessments(cumulative, 100L);

        assertThat(assessments).extracting("confidence")
                               .containsExactly(0.8, 0.6, 0.5);
    }

    @Test
    void allAssessments_emptyWhenAllBelowThreshold() {
        var cumulative = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.TERRAN_MARINE_RUSH, 0.2);

        var assessments = PatternClassifier.allAssessments(cumulative, 100L);

        assertThat(assessments).isEmpty();
    }

    @Test
    void allAssessments_emptyMapReturnsEmpty() {
        var cumulative  = new EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        var assessments = PatternClassifier.allAssessments(cumulative, 100L);
        assertThat(assessments).isEmpty();
    }


}
