package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.ReportKind;

import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.GrimFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceRuleTest {

    private static final long SECOND = 1000;
    private final ConfidenceRule rule = new ConfidenceRule();
    private final ConfidenceRule.Policy policy = ConfidenceRule.Policy.DEFAULT;

    /** A flag of one of Grim's checks, by its name, in the family Grim's name puts it in. */
    private static ConfidenceRule.Hit hit(long at, String grimCheck) {
        return new ConfidenceRule.Hit(at, GrimFamily.of(grimCheck), 1, 40, 19.5, grimCheck + ": d");
    }

    /** {@code count} flags of one check spread evenly over {@code seconds}. */
    private static List<ConfidenceRule.Hit> spread(String check, int count, int seconds, long start) {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hits.add(hit(start + i * seconds * SECOND / count, check));
        }
        return hits;
    }

    @Test
    @DisplayName("a long, steady run of one movement check is a hack: forty hovering flags over a minute")
    void sustainedFly() {
        List<ConfidenceRule.Hit> hits = spread("Simulation", 40, 60, 0);
        ConfidenceRule.Verdict verdict = rule.judge(hits, 40, 60 * SECOND, policy);
        assertThat(verdict.confident()).isTrue();
        assertThat(verdict.summary()).contains("Simulation");
    }

    @Test
    @DisplayName("172 flags in seven seconds are a burst, not a pattern: lag or a bug, not something sustained")
    void burst() {
        List<ConfidenceRule.Hit> hits = spread("Simulation", 172, 7, 0);
        assertThat(rule.judge(hits, 172, 8 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("eight seconds that happen to touch three clock stretches are still only eight seconds")
    void alignedBurst() {
        List<ConfidenceRule.Hit> hits = spread("Simulation", 160, 8, 4_000);
        assertThat(rule.judge(hits, 160, 12 * SECOND, policy).confident()).isFalse();
        assertThat(rule.judge(spread("Simulation", 160, 12, 4_000), 160, 17 * SECOND, policy).confident()).isTrue();
    }

    @Test
    @DisplayName("a few flags are never enough, however many checks they come from")
    void fewFlags() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        hits.addAll(spread("Simulation", 4, 60, 0));
        hits.addAll(spread("GroundSpoof", 4, 60, 0));
        hits.addAll(spread("NoFall", 4, 60, 0));
        assertThat(rule.judge(hits, 30, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("several independent checks agreeing need fewer flags than one check alone")
    void agreeing() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        hits.addAll(spread("Simulation", 12, 60, 0));
        hits.addAll(spread("GroundSpoof", 10, 60, 0));
        assertThat(rule.judge(hits, 25, 60 * SECOND, policy).confident()).isTrue();
        assertThat(rule.judge(spread("Simulation", 22, 60, 0), 25, 60 * SECOND, policy).confident())
                .as("the same number of flags from one check").isFalse();
    }

    @Test
    @DisplayName("flags made while the player lagged or the server did count for little: a lag burst is not a report")
    void lagExcused() {
        List<ConfidenceRule.Hit> laggy = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            laggy.add(new ConfidenceRule.Hit(i * SECOND, CheckType.GRIM_MOVEMENT, 1, policy.maxPing() + 50, 19.5, "d"));
        }
        assertThat(rule.judge(laggy, 60, 60 * SECOND, policy).confident()).as("a bad ping").isFalse();
        List<ConfidenceRule.Hit> slowServer = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            slowServer.add(new ConfidenceRule.Hit(i * SECOND, CheckType.GRIM_MOVEMENT, 1, 40, policy.minTps() - 2, "d"));
        }
        assertThat(rule.judge(slowServer, 60, 60 * SECOND, policy).confident()).as("a slow server").isFalse();
    }

    @Test
    @DisplayName("a client faking a bad ping to hide still gets reported: ping never exempts, it only devalues")
    void pingFaker() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            hits.add(new ConfidenceRule.Hit(i * 250L, CheckType.GRIM_MOVEMENT, 1, 900, 19.8, "Simulation: offset 0.3"));
        }
        assertThat(rule.judge(hits, 100, 100 * SECOND, policy).confident()).isTrue();
    }

    @Test
    @DisplayName("old flags fall out of the window")
    void window() {
        List<ConfidenceRule.Hit> hits = spread("Simulation", 40, 60, 0);
        assertThat(rule.judge(hits, 40, 60 * SECOND + policy.windowMillis() + 1, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("a violation level that has decayed away is not a hack still going on")
    void decayed() {
        List<ConfidenceRule.Hit> hits = spread("Simulation", 40, 60, 0);
        assertThat(rule.judge(hits, 2, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("only movement checks count: a hundred reach flags are not a movement hack")
    void onlyMovement() {
        assertThat(rule.judge(spread("Reach", 100, 60, 0), 90, 60 * SECOND, policy).confident()).isFalse();
    }

    @Test
    @DisplayName("the summary names the checks, their counts, the span and the last thing seen")
    void summary() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>(spread("Simulation", 30, 60, 0));
        hits.addAll(spread("GroundSpoof", 12, 60, 0));
        ConfidenceRule.Verdict verdict = rule.judge(hits, 50, 60 * SECOND, policy);
        assertThat(verdict.confident()).isTrue();
        assertThat(verdict.summary()).contains("Simulation x30").contains("GroundSpoof x12").contains("42 flags").contains("60 s");
    }

    @Test
    @DisplayName("a stricter policy needs more")
    void policy() {
        ConfidenceRule.Policy strict = new ConfidenceRule.Policy(120 * SECOND, 100, 15, 300, 17, 3, 5 * SECOND);
        assertThat(rule.judge(spread("Simulation", 40, 60, 0), 40, 60 * SECOND, strict).confident()).isFalse();
    }

    @Test
    @DisplayName("combat: reach and hitbox flags over half a minute, in separate fights, are a report of their own kind")
    void combat() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>(spread("Reach", 6, 30, 0));
        hits.addAll(spread("Hitboxes", 4, 30, 2 * SECOND));
        ConfidenceRule.Policy combat = ReportKind.COMBAT.policy(policy);
        ConfidenceRule.Verdict verdict = rule.judge(ReportKind.COMBAT, hits, 12, 32 * SECOND, combat);
        assertThat(verdict.confident()).isTrue();
        assertThat(verdict.summary()).contains("Reach").contains("Hitboxes");
        assertThat(rule.judge(ReportKind.MOVEMENT, hits, 12, 32 * SECOND, policy).confident())
                .as("combat flags do not make a movement report").isFalse();
    }

    @Test
    @DisplayName("combat: one fight's burst of reach is not enough")
    void combatBurst() {
        List<ConfidenceRule.Hit> hits = spread("Reach", 12, 3, 0);
        assertThat(rule.judge(ReportKind.COMBAT, hits, 12, 4 * SECOND, ReportKind.COMBAT.policy(policy)).confident()).isFalse();
    }

    @Test
    @DisplayName("world: multi-break and fast break over two stretches are a report; inventory, packet and client flags are nobody's kind")
    void world() {
        List<ConfidenceRule.Hit> hits = new ArrayList<>(spread("MultiBreak", 6, 40, 0));
        hits.addAll(spread("FastBreak", 6, 40, SECOND));
        assertThat(rule.judge(ReportKind.WORLD, hits, 12, 42 * SECOND, ReportKind.WORLD.policy(policy)).confident()).isTrue();
        assertThat(ReportKind.of(CheckType.GRIM_INVENTORY)).isEmpty();
        assertThat(ReportKind.of(CheckType.GRIM_PACKETS)).isEmpty();
        assertThat(ReportKind.of(CheckType.CLIENT)).isEmpty();
    }
}
