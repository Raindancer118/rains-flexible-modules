package de.raindancer.modules.voicebridge.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static de.raindancer.modules.voicebridge.rules.GroupJoinRule.Verdict.CANNOT_CHECK;
import static de.raindancer.modules.voicebridge.rules.GroupJoinRule.Verdict.JOIN;
import static de.raindancer.modules.voicebridge.rules.GroupJoinRule.Verdict.NEEDS_PASSWORD;
import static de.raindancer.modules.voicebridge.rules.GroupJoinRule.Verdict.WRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/** The same door Simple Voice Chat keeps: a password, or an invite from somebody already inside. */
class GroupJoinRuleTest {

    private final GroupJoinRule rule = new GroupJoinRule();

    @Test
    @DisplayName("an open group lets anybody in")
    void open() {
        assertThat(rule.judge(false, null, null, false)).isEqualTo(JOIN);
    }

    @Test
    @DisplayName("a locked group wants its password, and only that one")
    void locked() {
        assertThat(rule.judge(true, "secret", null, false)).isEqualTo(NEEDS_PASSWORD);
        assertThat(rule.judge(true, "secret", " ", false)).isEqualTo(NEEDS_PASSWORD);
        assertThat(rule.judge(true, "secret", "Secret", false)).isEqualTo(WRONG_PASSWORD);
        assertThat(rule.judge(true, "secret", "secret", false)).isEqualTo(JOIN);
    }

    @Test
    @DisplayName("an invite opens a locked group, as SVC's own invites carry the password")
    void invited() {
        assertThat(rule.judge(true, "secret", null, true)).isEqualTo(JOIN);
        assertThat(rule.judge(true, null, null, true)).isEqualTo(JOIN);
    }

    @Test
    @DisplayName("if the password cannot be read, a typed one is never waved through")
    void cannotCheck() {
        assertThat(rule.judge(true, null, "anything", false)).isEqualTo(CANNOT_CHECK);
    }
}
