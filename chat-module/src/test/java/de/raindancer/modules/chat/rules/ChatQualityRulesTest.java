package de.raindancer.modules.chat.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatQualityRulesTest {

    @Nested
    @DisplayName("caps")
    class Caps {

        @Test
        @DisplayName("refuses a message that is mostly capitals, once it is long enough")
        void refusesShouting() {
            Verdict verdict = ChatQualityRules.caps("THIS IS SHOUTING AT EVERYBODY", 70, 8);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.caps");
        }

        @Test
        @DisplayName("a short message is never refused, however loud")
        void shortMessageAllowed() {
            Verdict verdict = ChatQualityRules.caps("NO", 70, 8);

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("ordinary sentence-case text is allowed")
        void ordinaryTextAllowed() {
            Verdict verdict = ChatQualityRules.caps("Hey, how is everyone doing today?", 70, 8);

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("numbers and punctuation are not counted as letters")
        void punctuationIgnored() {
            Verdict verdict = ChatQualityRules.caps("12345!!! ??? ...", 70, 8);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("repeat")
    class Repeat {

        @Test
        @DisplayName("refuses the exact same message twice in a row")
        void refusesRepeat() {
            Verdict verdict = ChatQualityRules.repeat("hello there", "hello there");

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.repeat");
        }

        @Test
        @DisplayName("a different message is allowed")
        void allowsDifferentMessage() {
            Verdict verdict = ChatQualityRules.repeat("hello there", "goodbye");

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("nothing to compare against is allowed")
        void allowsFirstMessage() {
            Verdict verdict = ChatQualityRules.repeat("hello there", null);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("cooldown and slowmode")
    class Waiting {

        @Test
        @DisplayName("refuses within the cooldown window")
        void refusesTooSoon() {
            Verdict verdict = ChatQualityRules.cooldown(2_000L, 5);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.cooldown");
            assertThat(verdict.detail()).isEqualTo("3");
        }

        @Test
        @DisplayName("allows once the window has passed")
        void allowsAfterWindow() {
            Verdict verdict = ChatQualityRules.cooldown(6_000L, 5);

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("zero switches the cooldown off")
        void zeroIsOff() {
            Verdict verdict = ChatQualityRules.cooldown(0L, 0);

            assertThat(verdict.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("slowmode uses its own reason key")
        void slowmodeHasItsOwnKey() {
            Verdict verdict = ChatQualityRules.slowmode(1_000L, 10);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.slowmode");
        }
    }
}
