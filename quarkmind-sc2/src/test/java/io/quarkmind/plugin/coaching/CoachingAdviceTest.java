package io.quarkmind.plugin.coaching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingAdviceTest {

    @Test
    void domainEnum_hasFourValues() {
        assertThat(CoachingDomain.values()).containsExactlyInAnyOrder(
            CoachingDomain.BUILD, CoachingDomain.MILITARY,
            CoachingDomain.EXPAND, CoachingDomain.TECH);
    }

    @Test
    void urgencyTier_crisisHas2sLatencyCap() {
        assertThat(CoachingUrgencyTier.CRISIS.latencyCapMs()).isEqualTo(2000);
        assertThat(CoachingUrgencyTier.STRATEGIC.latencyCapMs()).isEqualTo(5000);
        assertThat(CoachingUrgencyTier.ECONOMIC.latencyCapMs()).isEqualTo(5000);
    }

    @Test
    void urgencyTier_crisisHas150FrameCooldown() {
        assertThat(CoachingUrgencyTier.CRISIS.cooldownFrames()).isEqualTo(150);
        assertThat(CoachingUrgencyTier.STRATEGIC.cooldownFrames()).isEqualTo(110);
        assertThat(CoachingUrgencyTier.ECONOMIC.cooldownFrames()).isEqualTo(110);
    }

    @Test
    void urgencyTier_ordering_crisisHighest() {
        assertThat(CoachingUrgencyTier.CRISIS.ordinal())
            .isLessThan(CoachingUrgencyTier.STRATEGIC.ordinal());
        assertThat(CoachingUrgencyTier.STRATEGIC.ordinal())
            .isLessThan(CoachingUrgencyTier.ECONOMIC.ordinal());
    }

    @Test
    void advice_verificationWindowFloor_clampedTo200() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.BUILD,
            null, 50);
        assertThat(advice.verificationWindowFrames()).isEqualTo(200);
    }

    @Test
    void advice_verificationWindowAboveFloor_unchanged() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.BUILD,
            null, 450);
        assertThat(advice.verificationWindowFrames()).isEqualTo(450);
    }

    @Test
    void advice_nullVerificationFields_nonVerifiable() {
        var advice = new CoachingAdvice("improve macro", CoachingDomain.BUILD,
            null, 450);
        assertThat(advice.isVerifiable()).isFalse();
    }

    @Test
    void advice_withUnitType_verifiable() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(io.quarkmind.domain.UnitType.STALKER, null, 3, 0), 450);
        assertThat(advice.isVerifiable()).isTrue();
    }

    @Test
    void advice_withBuildingType_verifiable() {
        var advice = new CoachingAdvice("expand", CoachingDomain.EXPAND,
            new CountDelta(null, io.quarkmind.domain.BuildingType.NEXUS, 1, 0), 450);
        assertThat(advice.isVerifiable()).isTrue();
    }

    @Test
    void advice_unitAndBuildingBothSet_verifiable() {
        var advice = new CoachingAdvice("mixed", CoachingDomain.BUILD,
                                        new CountDelta(io.quarkmind.domain.UnitType.STALKER, io.quarkmind.domain.BuildingType.NEXUS, 1, 0), 450);
        assertThat(advice.isVerifiable()).isTrue();
        var countDelta = (CountDelta) advice.verification();
        assertThat(countDelta.unitType()).isEqualTo(io.quarkmind.domain.UnitType.STALKER);
    }

    @Test
    void advice_nullVerification_notVerifiable() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        null, 450);
        assertThat(advice.isVerifiable()).isFalse();
        assertThat(advice.verification()).isNull();
    }
}
