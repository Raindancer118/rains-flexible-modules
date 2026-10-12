package de.raindancer.modules.guilds;

import de.raindancer.modules.guilds.rules.ShareRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default spelled out: a positional constructor with forty components is a mis-ordering waiting to happen. */
class GuildsSettingsTest {

    private final GuildsSettings defaults = GuildsSettings.DEFAULTS;

    @Test
    @DisplayName("founding costs nothing by default, so a server needs no economy")
    void money() {
        assertThat(defaults.foundingPrice()).isEqualTo("0");
        assertThat(defaults.bankEnabled()).isTrue();
        assertThat(defaults.bankMost()).isEmpty();
        assertThat(defaults.disbandShare()).isEqualTo(ShareRule.Mode.CONTRIBUTION);
        assertThat(defaults.officerLimit()).isEqualTo("1000");
        assertThat(defaults.memberLimit()).isEqualTo("100");
    }

    @Test
    void names() {
        assertThat(defaults.shortestName()).isEqualTo(3);
        assertThat(defaults.longestName()).isEqualTo(24);
        assertThat(defaults.shortestTag()).isEqualTo(2);
        assertThat(defaults.longestTag()).isEqualTo(5);
        assertThat(defaults.bannedWords()).isEmpty();
        assertThat(defaults.mostRanks()).isEqualTo(8);
    }

    @Test
    void timing() {
        assertThat(defaults.invitationHours()).isEqualTo(48);
        assertThat(defaults.applicationDays()).isEqualTo(7);
        assertThat(defaults.successionDays()).isEqualTo(30);
        assertThat(defaults.homeWarmup()).isEqualTo(5);
        assertThat(defaults.homeCooldown()).isEqualTo(60);
        assertThat(defaults.tagInNames()).isTrue();
        assertThat(defaults.announce()).isTrue();
    }

    @Test
    void levels() {
        assertThat(defaults.levelBase()).isEqualTo(1000);
        assertThat(defaults.levelGrowth()).isEqualTo(1.6);
        assertThat(defaults.topLevel()).isEqualTo(25);
        assertThat(defaults.membersAtFirst()).isEqualTo(8);
        assertThat(defaults.membersPerLevel()).isEqualTo(2);
        assertThat(defaults.mostMembers()).isEqualTo(50);
        assertThat(defaults.alliesAtFirst()).isEqualTo(1);
        assertThat(defaults.levelsPerAlly()).isEqualTo(5);
        assertThat(defaults.homeFromLevel()).isEqualTo(3);
        assertThat(defaults.hourlyCap()).isEqualTo(600);
        assertThat(defaults.sizeDamping()).isEqualTo(0.35);
        assertThat(defaults.xpPerMonster()).isEqualTo(2);
        assertThat(defaults.xpPerBoss()).isEqualTo(250);
        assertThat(defaults.xpPerAdvancement()).isEqualTo(25);
        assertThat(defaults.xpPerMinute()).isEqualTo(1);
    }

    @Test
    @DisplayName("the further sources: deposits earn nothing unless the owner says so")
    void progress() {
        GuildProgressSettings more = GuildProgressSettings.DEFAULTS;
        assertThat(more.perOre()).isEqualTo(1);
        assertThat(more.perFish()).isEqualTo(3);
        assertThat(more.perBreed()).isEqualTo(2);
        assertThat(more.perTrade()).isEqualTo(2);
        assertThat(more.perHarvest()).isEqualTo(1);
        assertThat(more.perEnchantLevel()).isEqualTo(5);
        assertThat(more.perRaid()).isEqualTo(200);
        assertThat(more.perRivalKill()).isEqualTo(50);
        assertThat(more.perThousandDeposited()).isZero();
        assertThat(more.togetherPercent()).isEqualTo(10);
        assertThat(more.togetherMost()).isEqualTo(50);
        assertThat(more.togetherRadius()).isEqualTo(48);
        assertThat(more.contracts()).isTrue();
        assertThat(more.contractsPerWeek()).isEqualTo(3);
        assertThat(more.contractRewardPercent()).isEqualTo(25);
        assertThat(more.contractSizePercent()).isEqualTo(100);
    }
}
