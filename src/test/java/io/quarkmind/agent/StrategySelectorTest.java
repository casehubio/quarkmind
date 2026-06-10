package io.quarkmind.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySelectorTest {

    StrategySelector selector;

    @BeforeEach
    void setUp() {
        selector = new StrategySelector();
    }

    @Test
    void defaultSelectedId_isDrools() {
        assertThat(selector.getSelectedId()).isEqualTo("strategy.drools");
    }

    @Test
    void defaultContext_isUnknown() {
        assertThat(selector.getOpponentContext())
            .isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
    }

    @Test
    void isSelected_trueForDefault() {
        assertThat(selector.isSelected("strategy.drools")).isTrue();
    }

    @Test
    void isSelected_falseForOther() {
        assertThat(selector.isSelected("strategy.early-pressure")).isFalse();
    }

    @Test
    void selectForGame_updatesSelection() {
        selector.selectForGame("strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
        assertThat(selector.getSelectedId()).isEqualTo("strategy.early-pressure");
        assertThat(selector.getOpponentContext()).isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
        assertThat(selector.isSelected("strategy.early-pressure")).isTrue();
        assertThat(selector.isSelected("strategy.drools")).isFalse();
    }

    @Test
    void claimCheckpoint_firstCallReturnsTrue() {
        assertThat(selector.claimCheckpoint()).isTrue();
    }

    @Test
    void claimCheckpoint_subsequentCallsReturnFalse() {
        selector.claimCheckpoint();
        assertThat(selector.claimCheckpoint()).isFalse();
        assertThat(selector.claimCheckpoint()).isFalse();
    }

    @Test
    void reset_clearsSelection() {
        selector.selectForGame("strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
        selector.claimCheckpoint();
        selector.reset();

        assertThat(selector.getSelectedId()).isEqualTo("strategy.drools");
        assertThat(selector.getOpponentContext()).isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        assertThat(selector.isCheckpointFired()).isFalse();
    }

    @Test
    void reset_allowsCheckpointToFireAgain() {
        selector.claimCheckpoint();
        selector.reset();
        assertThat(selector.claimCheckpoint()).isTrue();
    }
}
