package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRuleTest {

    private final ActionRule rule = new ActionRule();
    private final AntiCheatSettings defaults = AntiCheatSettings.DEFAULTS;

    private static AntiCheatSettings with(boolean autoBan, List<String> disabled, List<String> silent, int scale) {
        AntiCheatSettings d = AntiCheatSettings.DEFAULTS;
        return new AntiCheatSettings(d.enabled(), d.packetTap(), d.minTps(), d.maxPing(), d.exemptBedrock(),
                d.alerts(), d.setbacks(), d.cancel(), d.autoKick(), autoBan, d.banLength(), scale, d.evidence(),
                d.evidencePerPlayer(), disabled, silent, d.experimentalChecks(), d.reachLeniency(), d.timerLeniency(),
                d.maxCps(), d.blockedBrands(), d.blockedChannels(), d.kickBlockedClients(), d.announceBrands());
    }

    @Test
    @DisplayName("a first fly flag sets back but does not alert yet; past its alert level it alerts")
    void escalates() {
        ActionRule.Decision first = rule.decide(CheckType.FLY, 1, defaults);
        assertThat(first.act()).isTrue();
        assertThat(first.alert()).isFalse();
        assertThat(rule.decide(CheckType.FLY, CheckType.FLY.alertAt(), defaults).alert()).isTrue();
    }

    @Test
    @DisplayName("kick at the kick level; never a ban unless the owner switched bans on")
    void punishes() {
        ActionRule.Decision atKick = rule.decide(CheckType.FLY, CheckType.FLY.kickAt(), defaults);
        assertThat(atKick.kick()).isTrue();
        assertThat(atKick.ban()).isFalse();
        assertThat(rule.decide(CheckType.FLY, CheckType.FLY.banAt(), defaults).ban()).isFalse();

        ActionRule.Decision banned = rule.decide(CheckType.FLY, CheckType.FLY.banAt(), with(true, List.of(), List.of(), 100));
        assertThat(banned.ban()).isTrue();
        assertThat(banned.kick()).as("a ban already throws them out").isFalse();
    }

    @Test
    @DisplayName("the punish scale moves kick levels")
    void scale() {
        assertThat(rule.decide(CheckType.FLY, CheckType.FLY.kickAt(), with(false, List.of(), List.of(), 200)).kick()).isFalse();
        assertThat(rule.decide(CheckType.FLY, CheckType.FLY.kickAt() / 2.0, with(false, List.of(), List.of(), 50)).kick()).isTrue();
    }

    @Test
    @DisplayName("a switched-off check does nothing; a watched-only one alerts and nothing more")
    void disabledAndSilent() {
        assertThat(rule.decide(CheckType.FLY, 100, with(true, List.of("fly"), List.of(), 100))).isEqualTo(ActionRule.Decision.NOTHING);
        ActionRule.Decision silent = rule.decide(CheckType.FLY, 100, with(true, List.of(), List.of("FLY"), 100));
        assertThat(silent.alert()).isTrue();
        assertThat(silent.act()).isFalse();
        assertThat(silent.kick()).isFalse();
        assertThat(silent.ban()).isFalse();
    }

    @Test
    @DisplayName("experimental checks only ever alert")
    void experimental() {
        ActionRule.Decision aim = rule.decide(CheckType.AIM, 1000, with(true, List.of(), List.of(), 100));
        assertThat(aim.alert()).isTrue();
        assertThat(aim.act() || aim.kick() || aim.ban()).isFalse();
    }

    @Test
    @DisplayName("every check has a key that finds it again, and no two share one")
    void keys() {
        for (CheckType check : CheckType.values()) {
            assertThat(CheckType.find(check.key())).contains(check);
            assertThat(CheckType.find(check.title())).contains(check);
        }
        assertThat(java.util.Arrays.stream(CheckType.values()).map(CheckType::key).distinct().count())
                .isEqualTo(CheckType.values().length);
    }
}
