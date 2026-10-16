package de.raindancer.modules.ctf.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("levels from experience")
class LevelRuleTest {

    private final LevelRule rule = new LevelRule();

    @Test
    @DisplayName("level one at nothing; each level costs a little more than the one before")
    void levels() {
        assertThat(rule.level(0)).isEqualTo(1);
        assertThat(rule.level(99)).isEqualTo(1);
        assertThat(rule.level(100)).isEqualTo(2);
        assertThat(rule.level(299)).isEqualTo(2);
        assertThat(rule.level(300)).isEqualTo(3);
        assertThat(rule.xpFor(3)).isEqualTo(300);
        assertThat(rule.xpFor(4)).isEqualTo(600);
        for (int level = 1; level < 60; level++) {
            assertThat(rule.level(rule.xpFor(level))).isEqualTo(level);
            assertThat(rule.level(rule.xpFor(level + 1) - 1)).isEqualTo(level);
        }
    }

    @Test
    @DisplayName("experience for a round: the deeds, and a bonus for winning")
    void earned() {
        assertThat(rule.earned(2, 1, 3, 1, true)).isEqualTo(2 * 30 + 15 + 3 * 5 + 10 + 50 + 20);
        assertThat(rule.earned(0, 0, 0, 0, false)).as("turning up is worth something").isEqualTo(20);
    }

    @Test
    @DisplayName("progress inside a level, for a bar")
    void progress() {
        assertThat(rule.progress(200)).isEqualTo(0.5f);
        assertThat(rule.progress(0)).isZero();
    }
}
