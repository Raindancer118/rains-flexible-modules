package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRuleTest {

    private final ActionRule rule = new ActionRule();
    /** An owner who switched kicks on: the defaults leave them off. */
    private final AntiCheatSettings defaults = with(false, List.of(), List.of(), 100);

    private static AntiCheatSettings with(boolean autoBan, List<String> disabled, List<String> silent, int scale) {
        return with(autoBan, AntiCheatSettings.DEFAULTS.punishByRules(), disabled, silent, scale);
    }

    private static AntiCheatSettings with(boolean autoBan, boolean byRules, List<String> disabled, List<String> silent, int scale) {
        AntiCheatSettings d = AntiCheatSettings.DEFAULTS;
        return new AntiCheatSettings(d.enabled(), d.packetTap(), d.minTps(), d.alerts(), true, autoBan, d.banLength(),
                byRules, d.cheatingRule(), scale, d.evidence(), d.evidencePerPlayer(), disabled, silent,
                d.experimentalChecks(), d.blockedBrands(), d.blockedChannels(), d.kickBlockedClients(), d.announceBrands(),
                d.antiEsp(), d.antiEspRange(), d.autoReport(), d.autoReportFlags(), d.autoReportSeconds(),
                d.autoReportLevel(), d.autoReportMaxPing());
    }

    @Test
    @DisplayName("a first flag does not alert yet; past its family's alert level it alerts")
    void escalates() {
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, 1, defaults).alert()).isFalse();
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.alertAt(), defaults).alert()).isTrue();
    }

    @Test
    @DisplayName("kick at the kick level; never anything at the ban level unless the owner switched bans on")
    void punishes() {
        ActionRule.Decision atKick = rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.kickAt(), defaults);
        assertThat(atKick.kick()).isTrue();
        assertThat(atKick.ban()).isFalse();
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.banAt(), defaults).ban())
                .as("bans stay off until the owner switches them on, rules or not").isFalse();
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.banAt(), with(false, true, List.of(), List.of(), 100)).ban())
                .as("punishing by the rules only says what a ban level costs, not that it costs anything").isFalse();
        assertThat(rule.decide(CheckType.GRIM_INVENTORY, 1000, defaults).ban()).as("a check without a ban level never").isFalse();

        ActionRule.Decision banned = rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.banAt(), with(true, List.of(), List.of(), 100));
        assertThat(banned.ban()).isTrue();
        assertThat(banned.kick()).as("a ban already throws them out").isFalse();
    }

    @Test
    @DisplayName("the punish scale moves kick levels")
    void scale() {
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.kickAt(), with(false, List.of(), List.of(), 200)).kick()).isFalse();
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.kickAt() / 2.0, with(false, List.of(), List.of(), 50)).kick()).isTrue();
    }

    @Test
    @DisplayName("a switched-off check does nothing; a watched-only one alerts and nothing more")
    void disabledAndSilent() {
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, 100, with(true, List.of("movement"), List.of(), 100))).isEqualTo(ActionRule.Decision.NOTHING);
        ActionRule.Decision silent = rule.decide(CheckType.GRIM_MOVEMENT, 100, with(true, List.of(), List.of("MOVEMENT"), 100));
        assertThat(silent.alert()).isTrue();
        assertThat(silent.kick()).isFalse();
        assertThat(silent.ban()).isFalse();
    }

    @Test
    @DisplayName("out of the box nothing is ever kicked or banned: flags only alert, staff decide")
    void defaultsNeverPunish() {
        AntiCheatSettings fresh = AntiCheatSettings.DEFAULTS;
        for (CheckType check : CheckType.values()) {
            for (double level : new double[]{1, check.alertAt(), 10, 100, 10_000}) {
                ActionRule.Decision decision = rule.decide(check, level, fresh);
                assertThat(decision.kick()).as("%s at %.0f kicks", check, level).isFalse();
                assertThat(decision.ban()).as("%s at %.0f bans", check, level).isFalse();
            }
        }
        assertThat(rule.decide(CheckType.GRIM_MOVEMENT, CheckType.GRIM_MOVEMENT.alertAt(), fresh).alert()).isTrue();
    }
}
