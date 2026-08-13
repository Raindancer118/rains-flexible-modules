package de.raindancer.modules.mannequin.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurabilityRuleTest {

    private final DurabilityRule rule = new DurabilityRule();

    @Test
    @DisplayName("no Unbreaking always takes the damage")
    void noUnbreakingAlwaysTakesIt() {
        Random alwaysHighest = new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        assertThat(rule.shouldTakeDamage(0, alwaysHighest)).isTrue();
    }

    @Test
    @DisplayName("a one-in-(n+1) roll of zero takes the damage")
    void aZeroRollTakesTheDamage() {
        Random alwaysZero = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        assertThat(rule.shouldTakeDamage(3, alwaysZero)).isTrue();
    }

    @Test
    @DisplayName("any other roll skips the damage")
    void anyOtherRollSkipsIt() {
        Random neverZero = new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        assertThat(rule.shouldTakeDamage(3, neverZero)).isFalse();
    }

    @Test
    @DisplayName("over many rolls, unbreaking 3 takes damage roughly a quarter of the time")
    void statisticallyAQuarter() {
        Random seeded = new Random(42);
        int taken = 0;
        int rounds = 100_000;
        for (int i = 0; i < rounds; i++) {
            if (rule.shouldTakeDamage(3, seeded)) {
                taken++;
            }
        }
        double ratio = taken / (double) rounds;
        assertThat(ratio).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void nullRandomIsRejected() {
        assertThatThrownBy(() -> rule.shouldTakeDamage(1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reaching or passing max durability would break the piece")
    void wouldBreakAtOrAboveMax() {
        assertThat(rule.wouldBreak(10, 10)).isTrue();
        assertThat(rule.wouldBreak(11, 10)).isTrue();
        assertThat(rule.wouldBreak(9, 10)).isFalse();
        assertThat(rule.wouldBreak(5, 0)).isFalse();
    }
}
