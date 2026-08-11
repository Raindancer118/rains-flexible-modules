package de.raindancer.modules.rtp.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.world.protection.FlagPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a random teleport may happen at all, before anything is spent standing still for it.
 */
class RtpRuleTest {

    private final RtpRule rule = new RtpRule();

    @Nested
    @DisplayName("which world")
    class Worlds {

        @Test
        @DisplayName("an ordinary world is allowed")
        void ordinaryWorldIsAllowed() {
            assertThat(rule.mayGo("world", List.of()).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("a world on the list is refused")
        void listedWorldIsRefused() {
            Verdict verdict = rule.mayGo("arena", List.of("arena", "lobby"));

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(RtpRule.WORLD_DISABLED);
        }

        @Test
        @DisplayName("the list is read case-insensitively")
        void caseInsensitive() {
            assertThat(rule.mayGo("ARENA", List.of("arena")).isRefused()).isTrue();
        }

        @Test
        @DisplayName("no world at all is refused")
        void noWorldIsRefused() {
            Verdict verdict = rule.mayGo(null, List.of());

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(RtpRule.NO_WORLD);
        }
    }

    @Nested
    @DisplayName("whether a landing is checked for safety")
    class SafetyPolicy {

        @Test
        @DisplayName("available: the player's own choice wins")
        void availableFollowsThePlayer() {
            assertThat(rule.effectiveSafeArrival(FlagPolicy.AVAILABLE, true)).isTrue();
            assertThat(rule.effectiveSafeArrival(FlagPolicy.AVAILABLE, false)).isFalse();
        }

        @Test
        @DisplayName("forced on: always checked, whatever the player asked for")
        void forcedOnAlwaysChecks() {
            assertThat(rule.effectiveSafeArrival(FlagPolicy.FORCED_ON, false)).isTrue();
            assertThat(rule.effectiveSafeArrival(FlagPolicy.FORCED_ON, true)).isTrue();
        }

        @Test
        @DisplayName("forced off: never checked, whatever the player asked for")
        void forcedOffNeverChecks() {
            assertThat(rule.effectiveSafeArrival(FlagPolicy.FORCED_OFF, true)).isFalse();
            assertThat(rule.effectiveSafeArrival(FlagPolicy.FORCED_OFF, false)).isFalse();
        }

        @Test
        @DisplayName("disabled behaves the same as forced off")
        void disabledBehavesLikeForcedOff() {
            assertThat(rule.effectiveSafeArrival(FlagPolicy.DISABLED, true)).isFalse();
        }

        @Test
        @DisplayName("a missing policy defaults to available, following the player's own choice")
        void nullPolicyDefaultsToAvailable() {
            assertThat(rule.effectiveSafeArrival(null, true)).isTrue();
            assertThat(rule.effectiveSafeArrival(null, false)).isFalse();
        }
    }

    @Test
    @DisplayName("a rule decides and does nothing else")
    void describesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
