package de.raindancer.modules.performance.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** When staff hear about lag: once when it starts, again only when it gets worse or something new is found. */
class NoticeRuleTest {

    private static final long MINUTE = Duration.ofMinutes(1).toMillis();

    private final NoticeRule rule = new NoticeRule(Duration.ofMinutes(10));

    @Test
    @DisplayName("the first time the server lags, staff are told")
    void first() {
        assertThat(rule.shouldTell(NoticeRule.Last.NEVER, 0, LagRule.State.LAGGING, Set.of("a"))).isTrue();
    }

    @Test
    @DisplayName("the same lag with the same findings is told once in ten minutes")
    void quietAfterwards() {
        NoticeRule.Last last = new NoticeRule.Last(0, LagRule.State.LAGGING, Set.of("a"));

        assertThat(rule.shouldTell(last, 5 * MINUTE, LagRule.State.LAGGING, Set.of("a"))).isFalse();
        assertThat(rule.shouldTell(last, 11 * MINUTE, LagRule.State.LAGGING, Set.of("a"))).isTrue();
    }

    @Test
    @DisplayName("a new finding is told at once — that is new information")
    void newFinding() {
        NoticeRule.Last last = new NoticeRule.Last(0, LagRule.State.LAGGING, Set.of("a"));

        assertThat(rule.shouldTell(last, MINUTE, LagRule.State.LAGGING, Set.of("a", "b"))).isTrue();
    }

    @Test
    @DisplayName("from a spike to lasting lag is worse, and told at once; back to a spike is not")
    void worse() {
        NoticeRule.Last spike = new NoticeRule.Last(0, LagRule.State.SPIKE, Set.of());
        NoticeRule.Last lagging = new NoticeRule.Last(0, LagRule.State.LAGGING, Set.of());

        assertThat(rule.shouldTell(spike, MINUTE, LagRule.State.LAGGING, Set.of())).isTrue();
        assertThat(rule.shouldTell(lagging, MINUTE, LagRule.State.SPIKE, Set.of())).isFalse();
    }

    @Test
    @DisplayName("a healthy or merely strained server is not reported")
    void healthy() {
        assertThat(rule.shouldTell(NoticeRule.Last.NEVER, 0, LagRule.State.HEALTHY, Set.of("a"))).isFalse();
        assertThat(rule.shouldTell(NoticeRule.Last.NEVER, 0, LagRule.State.STRAINED, Set.of("a"))).isFalse();
    }
}
