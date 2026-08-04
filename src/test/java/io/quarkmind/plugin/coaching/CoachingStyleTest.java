package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingStyleTest {

    private AgentDisposition boldIndependent() {
        return AgentDisposition.builder()
            .riskAppetite(ConscientiousnessTerm.BOLD.value())
            .socialOrient(ConscientiousnessTerm.INDEPENDENT.value())
            .build();
    }

    private AgentDisposition boldCollaborative() {
        return AgentDisposition.builder()
            .riskAppetite(ConscientiousnessTerm.BOLD.value())
            .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
            .build();
    }

    private AgentDisposition cautiousIndependent() {
        return AgentDisposition.builder()
            .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
            .socialOrient(ConscientiousnessTerm.INDEPENDENT.value())
            .build();
    }

    private AgentDisposition cautiousCollaborative() {
        return AgentDisposition.builder()
            .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
            .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
            .build();
    }

    @Test void crisis_boldIndependent_commander()      { assertThat(CoachingStyle.resolve(boldIndependent(), CoachingUrgencyTier.CRISIS)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void crisis_boldCollaborative_commander()     { assertThat(CoachingStyle.resolve(boldCollaborative(), CoachingUrgencyTier.CRISIS)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void crisis_cautiousIndependent_commander()   { assertThat(CoachingStyle.resolve(cautiousIndependent(), CoachingUrgencyTier.CRISIS)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void crisis_cautiousCollaborative_commander() { assertThat(CoachingStyle.resolve(cautiousCollaborative(), CoachingUrgencyTier.CRISIS)).isEqualTo(CoachingStyle.COMMANDER); }

    @Test void strategic_boldIndependent_commander()      { assertThat(CoachingStyle.resolve(boldIndependent(), CoachingUrgencyTier.STRATEGIC)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void strategic_boldCollaborative_rally()         { assertThat(CoachingStyle.resolve(boldCollaborative(), CoachingUrgencyTier.STRATEGIC)).isEqualTo(CoachingStyle.RALLY); }
    @Test void strategic_cautiousIndependent_commander()   { assertThat(CoachingStyle.resolve(cautiousIndependent(), CoachingUrgencyTier.STRATEGIC)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void strategic_cautiousCollaborative_rally()     { assertThat(CoachingStyle.resolve(cautiousCollaborative(), CoachingUrgencyTier.STRATEGIC)).isEqualTo(CoachingStyle.RALLY); }

    @Test void economic_boldIndependent_commander()      { assertThat(CoachingStyle.resolve(boldIndependent(), CoachingUrgencyTier.ECONOMIC)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void economic_boldCollaborative_rally()         { assertThat(CoachingStyle.resolve(boldCollaborative(), CoachingUrgencyTier.ECONOMIC)).isEqualTo(CoachingStyle.RALLY); }
    @Test void economic_cautiousIndependent_instructor()  { assertThat(CoachingStyle.resolve(cautiousIndependent(), CoachingUrgencyTier.ECONOMIC)).isEqualTo(CoachingStyle.INSTRUCTOR); }
    @Test void economic_cautiousCollaborative_mentor()    { assertThat(CoachingStyle.resolve(cautiousCollaborative(), CoachingUrgencyTier.ECONOMIC)).isEqualTo(CoachingStyle.MENTOR); }

    @Test void nullDisposition_economic_mentor()    { assertThat(CoachingStyle.resolve(null, CoachingUrgencyTier.ECONOMIC)).isEqualTo(CoachingStyle.MENTOR); }
    @Test void nullDisposition_crisis_commander()   { assertThat(CoachingStyle.resolve(null, CoachingUrgencyTier.CRISIS)).isEqualTo(CoachingStyle.COMMANDER); }
    @Test void nullDisposition_strategic_rally()    { assertThat(CoachingStyle.resolve(null, CoachingUrgencyTier.STRATEGIC)).isEqualTo(CoachingStyle.RALLY); }
}
