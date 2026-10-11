package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceRuleTest {

    private static final long SECOND = 1000;
    private final ConfidenceRule rule = new ConfidenceRule();
    private final ConfidenceRule.Policy policy = ConfidenceRule.Policy.DEFAULT;

    private static ConfidenceRule.Hit hit(long at, CheckType check) {
        return new ConfidenceRule.Hit(at, check, 1, 40, 19.5, "d");
    }

    /** {@code count} flags of one check spread evenly over {@code seconds}. */
    private static List<ConfidenceRule.Hit> spread(CheckType check, int count, int seconds, long start) {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hits.add(hit(start + i * seconds * SECOND / count, check));
        }
        return hits;
    }

    @Test
    @DisplayName("a long, steady run of one movement check is a hack: forty hovering flags over a minute")
    void sustainedFly() {
        List<ConfidenceRule.Hit> hits = spread(CheckType.FLY, 40, 60, 0);
        ConfidenceRule.Verdict verdict = rule.judge(hits, 40, 60 * SECOND, policy);
        assertThat(verdict.confident()).isTrue();
        assertThat(verdict.summary()).contains("fly");
    }

    @Test
    @DisplayName("172 flags in seven seconds are a burst, not a pattern: lag or a bug, not something sustained")
    void burst() {
        List<ConfidenceRule.Hit> hits = spread(CheckType.FLY, 172, 7, 0);
        assertThat(rule.judge(hits, 172, 8 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("eight seconds that happen to touch three clock stretches are still only eight seconds")
    void alignedBurst() {
        List<ConfidenceRule.Hit> hits = spread(CheckType.FLY, 160, 8, 4_000);
        assertThat(rule.judge(hits, 160, 12 * SECOND, policy).confident()).isFalse();
        assertThat(rule.judge(spread(CheckType.FLY, 160, 12, 4_000), 160, 17 * SECOND, policy).confident()).isTrue();
    }

    @Test
    @DisplayName("a few flags are never enough, however many checks they come from")
    void fewFlags() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        hits.addAll(spread(CheckType.FLY, 4, 60, 0));
        hits.addAll(spread(CheckType.SPEED, 4, 60, 0));
        hits.addAll(spread(CheckType.STRAFE, 4, 60, 0));
        assertThat(rule.judge(hits, 30, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("several independent checks agreeing need fewer flags than one check alone")
    void agreeing() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        hits.addAll(spread(CheckType.FLY, 12, 60, 0));
        hits.addAll(spread(CheckType.SPEED, 10, 60, 0));
        assertThat(rule.judge(hits, 25, 60 * SECOND, policy).confident()).isTrue();
        assertThat(rule.judge(spread(CheckType.FLY, 22, 60, 0), 25, 60 * SECOND, policy).confident())
                .as("the same number of flags from one check").isFalse();
    }

    @Test
    @DisplayName("flags made while the player lagged or the server did do not count")
    void lagExcused() {
        List<ConfidenceRule.Hit> laggy = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            laggy.add(new ConfidenceRule.Hit(i * SECOND, CheckType.FLY, 1, policy.maxPing() + 50, 19.5, "d"));
        }
        assertThat(rule.judge(laggy, 60, 60 * SECOND, policy).confident()).as("a bad ping").isFalse();
        List<ConfidenceRule.Hit> slowServer = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            slowServer.add(new ConfidenceRule.Hit(i * SECOND, CheckType.FLY, 1, 40, policy.minTps() - 2, "d"));
        }
        assertThat(rule.judge(slowServer, 60, 60 * SECOND, policy).confident()).as("a slow server").isFalse();
    }

    @Test
    @DisplayName("old flags fall out of the window")
    void window() {
        List<ConfidenceRule.Hit> hits = spread(CheckType.FLY, 40, 60, 0);
        assertThat(rule.judge(hits, 40, 60 * SECOND + policy.windowMillis() + 1, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("a violation level that has decayed away is not a hack still going on")
    void decayed() {
        List<ConfidenceRule.Hit> hits = spread(CheckType.FLY, 40, 60, 0);
        assertThat(rule.judge(hits, 2, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("only movement checks count: a hundred autoclicker flags are not a movement hack")
    void onlyMovement() {
        assertThat(rule.judge(spread(CheckType.AUTOCLICKER, 100, 60, 0), 90, 60 * SECOND, policy).confident()).isFalse();
        assertThat(rule.judge(spread(CheckType.SPRINT, 100, 60, 0), 90, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("the summary names the checks, their counts, the span and the last thing seen")
    void summary() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>(spread(CheckType.FLY, 30, 60, 0));
        hits.addAll(spread(CheckType.SPEED, 12, 60, 0));
        ConfidenceRule.Verdict verdict = rule.judge(hits, 50, 60 * SECOND, policy);
        assertThat(verdict.confident()).isTrue();
        assertThat(verdict.summary()).contains("fly x30").contains("speed x12").contains("42 flags").contains("60 s");
    }

    @Test
    @DisplayName("a stricter policy needs more")
    void policy() {
        ConfidenceRule.Policy strict = new ConfidenceRule.Policy(120 * SECOND, 100, 15, 300, 17, 3, 5 * SECOND);
        assertThat(rule.judge(spread(CheckType.FLY, 40, 60, 0), 40, 60 * SECOND, strict).confident()).isFalse();
    }
}
