package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerMiningBaselineTest {

    @Nested
    @DisplayName("before anything has been mined")
    class Empty {

        @Test
        @DisplayName("the baseline is zero rather than undefined")
        void startsAtZero() {
            assertThat(new ServerMiningBaseline().ratio()).isZero();
        }
    }

    @Nested
    @DisplayName("settling towards what the server actually does")
    class Settling {

        @Test
        @DisplayName("a server where nobody finds ore settles near zero")
        void settlesLow() {
            ServerMiningBaseline baseline = new ServerMiningBaseline();
            for (int i = 0; i < 5000; i++) {
                baseline.record(false);
            }
            assertThat(baseline.ratio()).isLessThan(0.001);
        }

        @Test
        @DisplayName("a server where a tenth of everything mined is ore settles near a tenth")
        void settlesAtTheRealRatio() {
            ServerMiningBaseline baseline = new ServerMiningBaseline();
            for (int i = 0; i < 5000; i++) {
                baseline.record(i % 10 == 0);
            }
            assertThat(baseline.ratio()).isBetween(0.08, 0.12);
        }

        @Test
        @DisplayName("recent behaviour outweighs old behaviour, so the number can recover")
        void oldBehaviourFades() {
            ServerMiningBaseline baseline = new ServerMiningBaseline();
            // A rough patch early on...
            for (int i = 0; i < 500; i++) {
                baseline.record(true);
            }
            double afterTheRoughPatch = baseline.ratio();
            // ...long since over.
            for (int i = 0; i < 5000; i++) {
                baseline.record(false);
            }
            assertThat(baseline.ratio())
                    .as("a spike from months ago should not still be inflating today's baseline")
                    .isLessThan(afterTheRoughPatch);
        }
    }

    @Test
    @DisplayName("the same sequence of observations always settles the same way")
    void isDeterministic() {
        ServerMiningBaseline first = new ServerMiningBaseline();
        ServerMiningBaseline second = new ServerMiningBaseline();
        for (int i = 0; i < 300; i++) {
            boolean ore = i % 7 == 0;
            first.record(ore);
            second.record(ore);
        }
        assertThat(first.ratio()).isEqualTo(second.ratio());
    }
}
