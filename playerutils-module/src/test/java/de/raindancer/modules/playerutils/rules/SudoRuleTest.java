package de.raindancer.modules.playerutils.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What may be put in somebody else's mouth. */
class SudoRuleTest {

    private final SudoRule rule = new SudoRule();
    private final List<String> blocked = List.of("op", "deop", "stop", "sudo", "lp");

    @Test
    @DisplayName("a blocked command is refused however it is written")
    void blocked() {
        assertThat(rule.refuse("op Raindancer118", blocked)).isPresent();
        assertThat(rule.refuse("/OP someone", blocked)).isPresent();
        assertThat(rule.refuse("minecraft:op x", blocked)).isPresent();
        assertThat(rule.refuse("luckperms:lp user x", blocked)).isPresent();
        assertThat(rule.refuse("sudo Sam c:hi", blocked)).as("no sudo chains").isPresent();
    }

    @Test
    @DisplayName("anything else, and any chat line, is allowed")
    void allowed() {
        assertThat(rule.refuse("spawn", blocked)).isEmpty();
        assertThat(rule.refuse("c:op me please", blocked)).isEmpty();
        assertThat(rule.refuse("operator", blocked)).as("a prefix is not the word").isEmpty();
    }

    @Test
    @DisplayName("chat lines are recognised with c: or chat:")
    void chat() {
        assertThat(SudoRule.chatLine("c:hello there")).contains("hello there");
        assertThat(SudoRule.chatLine("chat: hi")).contains("hi");
        assertThat(SudoRule.chatLine("spawn")).isEmpty();
        assertThat(SudoRule.chatLine("c:")).isEmpty();
    }

    @Test
    @DisplayName("the command loses its slash before it is run")
    void slash() {
        assertThat(SudoRule.commandLine("/spawn")).isEqualTo("spawn");
        assertThat(SudoRule.commandLine("  home  base ")).isEqualTo("home  base");
    }
}
