package de.raindancer.modules.mannequin.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalStrengthRuleTest {

    private final SignalStrengthRule rule = new SignalStrengthRule();

    @Test
    void zeroDamageIsZeroSignal() {
        assertThat(rule.signalFor(0.0, 20.0)).isZero();
    }

    @Test
    void theThresholdItselfIsMaxSignal() {
        assertThat(rule.signalFor(20.0, 20.0)).isEqualTo(15);
    }

    @Test
    @DisplayName("the owner's worked example: ~16 damage against a default threshold reads about 12")
    void theOwnersWorkedExample() {
        assertThat(rule.signalFor(16.0, 20.0)).isEqualTo(12);
    }

    @Test
    void aboveTheThresholdClampsAtFifteen() {
        assertThat(rule.signalFor(45.0, 20.0)).isEqualTo(15);
    }

    @Test
    void aDifferentThresholdScalesTheSameWay() {
        // Half the calibration point is still half the maximum signal.
        assertThat(rule.signalFor(5.0, 10.0)).isEqualTo(8);
    }

    @Test
    void negativeOrZeroInputsAreZero() {
        assertThat(rule.signalFor(-5.0, 20.0)).isZero();
        assertThat(rule.signalFor(10.0, 0.0)).isZero();
    }
}
