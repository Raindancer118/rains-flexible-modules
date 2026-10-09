package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.rules.RulePenalty;
import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.RuleBreachRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Breaking one of the server's rules: which rung, which permission it takes, and what the record says. */
class RuleBreachRuleTest {

    private final RuleBreachRule rule = new RuleBreachRule();
    private final ServerRule griefing = new ServerRule("g1", 2, "No griefing", "Leave it alone.",
            RulePenalty.ladder("warn, ban 3d, ban").orElseThrow());

    @Test
    @DisplayName("the first offence takes the first rung, the next the next, and past the top it stays there")
    void rungs() {
        assertThat(rule.breach(griefing, 0, "").orElseThrow().penalty().kind()).isEqualTo(PunishmentKind.WARNING);
        RuleBreachRule.Breach second = rule.breach(griefing, 1, "").orElseThrow();
        assertThat(second.penalty().length()).isEqualTo(Duration.ofDays(3));
        assertThat(second.offence()).isEqualTo(2);
        assertThat(rule.breach(griefing, 9, "").orElseThrow().penalty().isPermanent()).isTrue();
    }

    @Test
    @DisplayName("a rule without a ladder has nothing to hand out")
    void noLadder() {
        assertThat(rule.breach(new ServerRule("x", 1, "Be kind", "", java.util.List.of()), 0, "")).isEmpty();
    }

    @Test
    @DisplayName("the record says which rule, by number and title, and what the moderator added")
    void reason() {
        assertThat(rule.breach(griefing, 0, "").orElseThrow().reason()).isEqualTo("Broke rule 2: No griefing");
        assertThat(rule.breach(griefing, 0, " tore down Bo's house ").orElseThrow().reason())
                .isEqualTo("Broke rule 2: No griefing — tore down Bo's house");
    }

    @Test
    @DisplayName("each rung needs exactly the permission handing it out by hand would")
    void permissions() {
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.WARNING, null)))
                .isEqualTo(ModerationPermission.WARN);
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.KICK, null)))
                .isEqualTo(ModerationPermission.KICK);
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.MUTE, Duration.ofHours(1))))
                .isEqualTo(ModerationPermission.MUTE);
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.FREEZE, Duration.ofHours(1))))
                .isEqualTo(ModerationPermission.FREEZE);
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.BAN, Duration.ofDays(1))))
                .as("the ban limit then decides the length").isEqualTo(ModerationPermission.TEMPBAN);
        assertThat(RuleBreachRule.permissionFor(new RulePenalty(PunishmentKind.FINE, null, 100)))
                .isEqualTo(ModerationPermission.FINE);
    }
}
