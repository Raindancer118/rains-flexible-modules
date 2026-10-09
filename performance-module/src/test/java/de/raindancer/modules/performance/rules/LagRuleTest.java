package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.TickWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LagRuleTest {

    private final LagRule rule = new LagRule(45, 200);

    private static TickWindow ticks(double each, int count) {
        TickWindow window = new TickWindow(count);
        for (int i = 0; i < count; i++) {
            window.add(each);
        }
        return window;
    }

    @Test
    @DisplayName("comfortably under budget is healthy")
    void healthy() {
        assertThat(rule.judge(ticks(20, 600))).isEqualTo(LagRule.State.HEALTHY);
    }

    @Test
    @DisplayName("near the 50 ms budget is strained — still 20 TPS, but no headroom")
    void strained() {
        assertThat(rule.judge(ticks(47, 600))).isEqualTo(LagRule.State.STRAINED);
    }

    @Test
    @DisplayName("over budget on average is lagging — what Lilly's SMP had at 57 ms")
    void lagging() {
        assertThat(rule.judge(ticks(57, 600))).isEqualTo(LagRule.State.LAGGING);
    }

    @Test
    @DisplayName("a single very long tick in a healthy minute is a spike")
    void spike() {
        TickWindow window = ticks(15, 600);
        window.add(450);

        assertThat(rule.judge(window)).isEqualTo(LagRule.State.SPIKE);
    }

    @Test
    @DisplayName("lagging outranks a spike: the steady problem is the bigger one")
    void laggingFirst() {
        TickWindow window = ticks(60, 600);
        window.add(900);

        assertThat(rule.judge(window)).isEqualTo(LagRule.State.LAGGING);
    }

    @Test
    @DisplayName("too few ticks to say anything is healthy, not a false alarm right after start")
    void tooEarly() {
        assertThat(rule.judge(ticks(80, 10))).isEqualTo(LagRule.State.HEALTHY);
    }
}
