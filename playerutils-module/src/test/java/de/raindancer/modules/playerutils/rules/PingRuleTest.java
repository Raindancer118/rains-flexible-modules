package de.raindancer.modules.playerutils.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PingRuleTest {

    private final PingRule rule = new PingRule();

    @Test
    @DisplayName("lower is better, in bars and words")
    void grades() {
        assertThat(rule.grade(12).bars()).isEqualTo(5);
        assertThat(rule.grade(80).bars()).isEqualTo(4);
        assertThat(rule.grade(120).bars()).isEqualTo(3);
        assertThat(rule.grade(200).bars()).isEqualTo(2);
        assertThat(rule.grade(350).bars()).isEqualTo(1);
        assertThat(rule.grade(900).bars()).isZero();
        assertThat(rule.grade(-1).bars()).as("not measured yet").isZero();
    }

    @Test
    @DisplayName("the bar drawing has five cells, filled from the left")
    void drawing() {
        assertThat(rule.grade(80).drawn()).contains("▮▮▮▮").contains("▯");
    }
}
