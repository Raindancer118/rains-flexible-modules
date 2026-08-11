package de.raindancer.modules.moderation.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousCommandRuleTest {

    private final SuspiciousCommandRule rule = new SuspiciousCommandRule();

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("a bare watched command matches")
        void bareCommand() {
            assertThat(rule.matched("/seed", List.of("seed"))).contains("seed");
        }

        @Test
        @DisplayName("a watched command with arguments still matches, on the first word")
        void withArguments() {
            assertThat(rule.matched("/seed confirm", List.of("seed"))).contains("seed");
        }

        @Test
        @DisplayName("matching is case-insensitive on both sides")
        void caseInsensitive() {
            assertThat(rule.matched("/SEED", List.of("Seed"))).isPresent();
        }

        @Test
        @DisplayName("a plugin-qualified command still matches the bare name")
        void qualifiedCommand() {
            assertThat(rule.matched("/minecraft:seed", List.of("seed"))).contains("seed");
        }

        @Test
        @DisplayName("the leading slash is optional")
        void slashIsOptional() {
            assertThat(rule.matched("seed", List.of("seed"))).contains("seed");
        }
    }

    @Nested
    @DisplayName("typos of a long watched word")
    class Typos {

        @Test
        @DisplayName("a missing letter still matches")
        void missingLetter() {
            assertThat(rule.matched("/seedcraker", List.of("seedcracker"))).contains("seedcracker");
        }

        @Test
        @DisplayName("a swapped letter still matches")
        void swappedLetter() {
            assertThat(rule.matched("/sedcracker", List.of("seedcracker"))).contains("seedcracker");
        }

        @Test
        @DisplayName("a hyphenated spelling still matches")
        void hyphenated() {
            assertThat(rule.matched("/seed-cracker", List.of("seedcracker"))).contains("seedcracker");
        }

        @Test
        @DisplayName("too many differences is not a typo any more")
        void tooFarIsNotATypo() {
            assertThat(rule.matched("/somethingelse", List.of("seedcracker"))).isEmpty();
        }

        @Test
        @DisplayName("a short watched word is never fuzzed, so it cannot catch ordinary words")
        void shortWordsAreExactOnly() {
            // "seed" is three edits from nothing in particular, but two from "feed" and "seen" — both
            // ordinary things a player might actually type. Fuzzing a four-letter word would make the
            // queue mostly false positives.
            assertThat(rule.matched("/feed", List.of("seed"))).isEmpty();
            assertThat(rule.matched("/seen", List.of("seed"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("not matching")
    class NotMatching {

        @Test
        @DisplayName("a command that merely contains the word is not caught")
        void substringIsNotEnough() {
            // /msg seed_hunter hello must not be read as /seed — the first word is /msg.
            assertThat(rule.matched("/msg seed_hunter hello", List.of("seed"))).isEmpty();
        }

        @Test
        @DisplayName("an unrelated command does not match")
        void unrelatedCommand() {
            assertThat(rule.matched("/spawn", List.of("seed"))).isEmpty();
        }

        @Test
        @DisplayName("nothing typed, nothing watched, or both — none of it throws")
        void emptyInputs() {
            assertThat(rule.matched(null, List.of("seed"))).isEmpty();
            assertThat(rule.matched("", List.of("seed"))).isEmpty();
            assertThat(rule.matched("/seed", List.of())).isEmpty();
            assertThat(rule.matched("/seed", null)).isEmpty();
        }
    }

    @Test
    @DisplayName("a rule decides and does nothing else")
    void describesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
