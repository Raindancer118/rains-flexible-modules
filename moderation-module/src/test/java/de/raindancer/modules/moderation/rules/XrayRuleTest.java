package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XrayRuleTest {

    private final XrayRule rule = new XrayRule();

    @Nested
    @DisplayName("not enough to judge by yet")
    class TooEarly {

        @Test
        @DisplayName("finding one ore in a small window is luck, not a pattern")
        void oneOreIsNotEnough() {
            Verdict verdict = rule.mayBeFlagged(1, 10, 3, 8);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(XrayRule.NOT_ENOUGH_ORE_YET);
        }

        @Test
        @DisplayName("an empty window judges nothing")
        void emptyWindow() {
            assertThat(rule.mayBeFlagged(0, 0, 3, 8).isRefused()).isTrue();
        }
    }

    @Nested
    @DisplayName("the ratio itself")
    class Ratio {

        @Test
        @DisplayName("ordinary survival mining — mostly stone — is not flagged")
        void ordinaryMiningIsFine() {
            // Three diamonds in two hundred blocks is a good vein, not a pattern.
            Verdict verdict = rule.mayBeFlagged(3, 200, 3, 8);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo(XrayRule.RATIO_TOO_LOW);
        }

        @Test
        @DisplayName("a high ore ratio, past the minimum count, is flagged")
        void highRatioIsFlagged() {
            // Twenty ores in eighty blocks is a quarter of everything mined being valuable ore.
            Verdict verdict = rule.mayBeFlagged(20, 80, 3, 8);

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("exactly the threshold is flagged, not just past it")
        void exactlyAtTheThreshold() {
            // 8 ore in 100 blocks is exactly 8%.
            assertThat(rule.mayBeFlagged(8, 100, 3, 8).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("just under the threshold is not flagged")
        void justUnderTheThreshold() {
            assertThat(rule.mayBeFlagged(7, 100, 3, 8).isRefused()).isTrue();
        }
    }

    @Nested
    @DisplayName("the learned threshold")
    class LearnedThreshold {

        @Test
        @DisplayName("learning off: the configured threshold is used exactly")
        void learningOffUsesTheConfiguredValue() {
            int effective = rule.effectiveThresholdPercent(8, false, 0.20, 5);

            assertThat(effective).isEqualTo(8);
        }

        @Test
        @DisplayName("a quiet server whose players rarely find ore does not lower the floor")
        void learningNeverLowersTheFloor() {
            // 0.1% baseline * 5 is nowhere near 8% — the configured minimum still wins.
            int effective = rule.effectiveThresholdPercent(8, true, 0.001, 5);

            assertThat(effective).isEqualTo(8);
        }

        @Test
        @DisplayName("a terrain-rich server raises the bar above the configured minimum")
        void learningRaisesTheBarOnRichTerrain() {
            // A server whose players genuinely average 4% ore, times a multiplier of 5, is 20% —
            // higher than the 8% floor, so the learned number is what is actually used.
            int effective = rule.effectiveThresholdPercent(8, true, 0.04, 5);

            assertThat(effective).isEqualTo(20);
        }

        @Test
        @DisplayName("the multiplier is what turns 'above normal' into a real percentage")
        void multiplierScalesTheBaseline() {
            assertThat(rule.effectiveThresholdPercent(1, true, 0.05, 2)).isEqualTo(10);
            assertThat(rule.effectiveThresholdPercent(1, true, 0.05, 10)).isEqualTo(50);
        }

        @Test
        @DisplayName("the effective threshold is never above 100")
        void neverExceedsAHundred() {
            assertThat(rule.effectiveThresholdPercent(8, true, 0.9, 20)).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("a rule decides and does nothing else")
    void describesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
