package de.raindancer.modules.anticheat.rules;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.rules.RulePenalty;
import de.raindancer.core.moderation.rules.ServerRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheatingRuleTest {

    private static final List<RulePenalty> LADDER = List.of(new RulePenalty(PunishmentKind.BAN, Duration.ofDays(14)),
            new RulePenalty(PunishmentKind.BAN, null));
    private static final List<ServerRule> RULES = List.of(
            new ServerRule("kind", 1, "Be kind", "No insults.", List.of(new RulePenalty(PunishmentKind.WARNING, null))),
            new ServerRule("lag", 2, "Keep the server running", "No lag machines or crash exploits.", LADDER),
            new ServerRule("cheat", 3, "No cheating", "No hacked clients, x-ray or exploits.", LADDER),
            new ServerRule("bare", 4, "No hacking", "Seriously.", List.of()));

    private final CheatingRule rule = new CheatingRule();

    @Test
    @DisplayName("'auto' finds the rule whose title is about cheating")
    void auto() {
        assertThat(rule.choose("auto", RULES)).map(ServerRule::id).contains("cheat");
        assertThat(rule.choose("", RULES)).as("blank is auto").map(ServerRule::id).contains("cheat");
    }

    @Test
    @DisplayName("a number picks that rule as players see it in /rules")
    void byNumber() {
        assertThat(rule.choose("2", RULES)).map(ServerRule::id).contains("lag");
        assertThat(rule.choose("9", RULES)).isEmpty();
    }

    @Test
    @DisplayName("a rule without punishments has nothing to hand out, so it is not chosen")
    void noLadder() {
        assertThat(rule.choose("4", RULES)).isEmpty();
        assertThat(rule.choose("auto", List.of(RULES.get(3)))).isEmpty();
    }

    @Test
    @DisplayName("no rules at all, or 'off', is no rule")
    void none() {
        assertThat(rule.choose("auto", List.of())).isEmpty();
        assertThat(rule.choose("off", RULES)).isEmpty();
    }

    @Test
    @DisplayName("one player is punished by the rules at most once every five minutes, so one burst is one offence")
    void cooldown() {
        assertThat(rule.mayPunishAgain(null, 1_000_000)).isTrue();
        assertThat(rule.mayPunishAgain(1_000_000L, 1_000_000 + 299_000)).isFalse();
        assertThat(rule.mayPunishAgain(1_000_000L, 1_000_000 + 300_000)).isTrue();
    }
}
