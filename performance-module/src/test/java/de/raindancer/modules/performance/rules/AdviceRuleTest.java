package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.Cause;
import de.raindancer.modules.performance.model.Fix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Server-wide advice: what to change when no single chunk explains a slow tick. */
class AdviceRuleTest {

    private final AdviceRule rule = new AdviceRule(6, 25);

    private static Attribution mostly(Cause cause, int of, Cause rest) {
        Attribution attribution = new Attribution();
        for (int i = 0; i < 100; i++) {
            attribution.count(i < of ? cause : rest);
        }
        return attribution;
    }

    @Test
    @DisplayName("mostly entities and chunks while lagging: a smaller simulation distance, two at a time")
    void simulationDistance() {
        Attribution busy = mostly(Cause.vanilla(Cause.Area.ENTITIES), 70, Cause.vanilla(Cause.Area.CHUNKS));

        assertThat(rule.advice(busy, LagRule.State.LAGGING, 10)).contains(Fix.simulationDistance(8));
    }

    @Test
    @DisplayName("never below the floor an owner set, and nothing to offer at the floor")
    void floor() {
        Attribution busy = mostly(Cause.vanilla(Cause.Area.ENTITIES), 90, Cause.vanilla(Cause.Area.CHUNKS));

        assertThat(rule.advice(busy, LagRule.State.LAGGING, 7)).contains(Fix.simulationDistance(6));
        assertThat(rule.advice(busy, LagRule.State.LAGGING, 6)).doesNotContain(Fix.simulationDistance(4));
    }

    @Test
    @DisplayName("a plugin with a quarter of the busy time is named — a smaller distance would not help there")
    void plugin() {
        Attribution busy = mostly(Cause.plugin("LaggyHoppers"), 40, Cause.vanilla(Cause.Area.ENTITIES));

        assertThat(rule.advice(busy, LagRule.State.LAGGING, 10))
                .containsExactly(Fix.suspectPlugin("LaggyHoppers", 40));
    }

    @Test
    @DisplayName("a healthy or merely strained server gets no server-wide change offered")
    void notLagging() {
        Attribution busy = mostly(Cause.vanilla(Cause.Area.ENTITIES), 90, Cause.vanilla(Cause.Area.CHUNKS));

        assertThat(rule.advice(busy, LagRule.State.HEALTHY, 10)).isEmpty();
        assertThat(rule.advice(busy, LagRule.State.STRAINED, 10)).isEmpty();
    }
}
