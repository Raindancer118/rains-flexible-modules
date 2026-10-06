package de.raindancer.modules.chat.rules;

import de.raindancer.core.ui.prompt.Parsed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What /poll reads, what it refuses, and how a result is drawn. */
class PollRuleTest {

    private final PollRule rule = new PollRule();
    private static final Duration DEFAULT = Duration.ofMinutes(2);

    @Test
    @DisplayName("a question and answers, split by bars, take the default time")
    void plain() {
        Parsed<PollRule.Request> read = rule.read("Best mob? | Creeper | Axolotl | Warden", DEFAULT);
        assertThat(read.isOk()).isTrue();
        assertThat(read.value().question()).isEqualTo("Best mob?");
        assertThat(read.value().answers()).containsExactly("Creeper", "Axolotl", "Warden");
        assertThat(read.value().lasting()).isEqualTo(DEFAULT);
    }

    @Test
    @DisplayName("a length in front sets how long it runs")
    void withALength() {
        Parsed<PollRule.Request> read = rule.read("5m Pizza tonight? | Yes | No", DEFAULT);
        assertThat(read.value().lasting()).isEqualTo(Duration.ofMinutes(5));
        assertThat(read.value().question()).isEqualTo("Pizza tonight?");
    }

    @Test
    @DisplayName("a question with no answers becomes yes or no")
    void yesNo() {
        Parsed<PollRule.Request> read = rule.read("Should we raid the end?", DEFAULT);
        assertThat(read.value().answers()).containsExactly("Yes", "No");
    }

    @Test
    @DisplayName("refusals say what to fix")
    void refusals() {
        assertThat(rule.read("", DEFAULT).isOk()).isFalse();
        assertThat(rule.read("Q | only one", DEFAULT).problem()).contains("two");
        assertThat(rule.read("Q | a | b | c | d | e | f | g", DEFAULT).problem()).contains("6");
        assertThat(rule.read("Q | same | Same", DEFAULT).problem()).contains("twice");
        assertThat(rule.read("Q | a | ", DEFAULT).problem()).contains("empty");
        assertThat(rule.read("Q | " + "x".repeat(40) + " | b", DEFAULT).problem()).contains("24");
        assertThat(rule.read("10h Q | a | b", DEFAULT).problem()).contains("an hour");
    }

    @Test
    @DisplayName("a result bar is as full as the share, and an empty poll draws empty bars")
    void bars() {
        assertThat(PollRule.bar(0.5, 10)).isEqualTo("█████░░░░░");
        assertThat(PollRule.bar(1.0, 10)).isEqualTo("██████████");
        assertThat(PollRule.bar(0.0, 10)).isEqualTo("░░░░░░░░░░");
        assertThat(PollRule.bar(Double.NaN, 4)).isEqualTo("░░░░");
    }

    @Test
    @DisplayName("the answers a draft from the menu can start with")
    void drafts() {
        assertThat(rule.check("Q?", List.of("a", "b"), Duration.ofMinutes(1)).isAllowed()).isTrue();
        assertThat(rule.check("", List.of("a", "b"), Duration.ofMinutes(1)).reason()).isEqualTo("chat.poll.no-question");
        assertThat(rule.check("Q?", List.of("a"), Duration.ofMinutes(1)).reason()).isEqualTo("chat.poll.too-few");
    }
}
