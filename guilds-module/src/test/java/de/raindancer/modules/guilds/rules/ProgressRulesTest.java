package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.XpSource;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressRulesTest {

    @Nested
    @DisplayName("what activity is worth")
    class Worth {

        private final ActivityRule rule = new ActivityRule();
        private final GuildsSettings base = GuildsSettings.DEFAULTS;
        private final GuildProgressSettings more = GuildProgressSettings.DEFAULTS;

        @Test
        void eachSource() {
            assertThat(rule.worth(Activity.of(XpSource.MONSTER, "zombie"), base, more)).isEqualTo(2);
            assertThat(rule.worth(Activity.of(XpSource.BOSS, "wither"), base, more)).isEqualTo(250);
            assertThat(rule.worth(Activity.of(XpSource.PLAY, 3), base, more)).isEqualTo(3);
            assertThat(rule.worth(Activity.of(XpSource.FISH, 2), base, more)).isEqualTo(6);
            assertThat(rule.worth(Activity.of(XpSource.ENCHANT, 3), base, more)).as("5 per level spent").isEqualTo(15);
            assertThat(rule.worth(Activity.of(XpSource.RIVAL_KILL, 1), base, more)).isEqualTo(50);
            assertThat(rule.worth(Activity.of(XpSource.DEPOSIT, 5), base, more)).as("off by default").isZero();
        }

        @Test
        @DisplayName("ores are worth their rarity, and both stone and deepslate ores are the same ore")
        void ores() {
            assertThat(ActivityRule.oreOf(Material.DEEPSLATE_DIAMOND_ORE)).contains("diamond");
            assertThat(ActivityRule.oreOf(Material.DIAMOND_ORE)).contains("diamond");
            assertThat(ActivityRule.oreOf(Material.STONE)).isEmpty();
            assertThat(rule.worth(Activity.of(XpSource.MINE, "diamond"), base, more)).isEqualTo(8);
            assertThat(rule.worth(Activity.of(XpSource.MINE, "coal"), base, more)).isEqualTo(1);
            assertThat(rule.worth(Activity.of(XpSource.MINE, "ancient-debris"), base, more)).isEqualTo(15);
        }

        @Test
        void neverOverflows() {
            assertThat(rule.worth(new Activity(XpSource.BOSS, "", Long.MAX_VALUE), base, more)).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("playing together")
    class Together {

        @Test
        void bonus() {
            TogetherRule rule = new TogetherRule(10, 50);
            assertThat(rule.bonusPercent(0)).isZero();
            assertThat(rule.bonusPercent(2)).isEqualTo(20);
            assertThat(rule.bonusPercent(9)).isEqualTo(50);
            assertThat(TogetherRule.withBonus(100, 20)).isEqualTo(120);
            assertThat(TogetherRule.withBonus(3, 50)).isEqualTo(4);
            assertThat(TogetherRule.withBonus(0, 50)).isZero();
            assertThat(TogetherRule.withBonus(Long.MAX_VALUE, 50)).isPositive();
        }
    }

    @Nested
    @DisplayName("weekly contracts")
    class Contracts {

        private final ContractRule rule = new ContractRule();

        @Test
        @DisplayName("weeks start on Mondays")
        void weeks() {
            LocalDate monday = LocalDate.of(2026, 10, 12);
            assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            long start = monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            assertThat(ContractRule.weekOf(start)).isEqualTo(ContractRule.weekOf(start + 6 * 86_400_000L + 86_399_999L));
            assertThat(ContractRule.weekOf(start - 1)).isEqualTo(ContractRule.weekOf(start) - 1);
            assertThat(ContractRule.weekEnds(ContractRule.weekOf(start))).isEqualTo(start + 7 * 86_400_000L);
        }

        @Test
        @DisplayName("the same guild and week always get the same contracts, of different kinds")
        void deterministic() {
            List<Contract> once = rule.draw("elves", 2911, 5, 3, 100, 500);
            List<Contract> again = rule.draw("elves", 2911, 5, 3, 100, 500);
            assertThat(once).isEqualTo(again).hasSize(3);
            Set<XpSource> kinds = once.stream().map(Contract::source).collect(Collectors.toSet());
            assertThat(kinds).hasSize(3);
            boolean someOtherWeekDiffers = false;
            for (long week = 2912; week < 2930; week++) {
                someOtherWeekDiffers |= !rule.draw("elves", week, 5, 3, 100, 500).equals(once);
            }
            assertThat(someOtherWeekDiffers).isTrue();
        }

        @Test
        @DisplayName("contracts grow with members, but less than linearly, and round to numbers people say")
        void sizes() {
            ContractRule.Template iron = ContractRule.POOL.stream().filter(each -> each.what().equals("iron")).findFirst().orElseThrow();
            assertThat(ContractRule.size(iron, 1, 100)).isEqualTo(40);
            long sixteen = ContractRule.size(iron, 16, 100);
            assertThat(sixteen).isEqualTo(320);
            assertThat(ContractRule.size(iron, 16, 50)).isEqualTo(160);
            assertThat(ContractRule.size(ContractRule.POOL.getFirst(), 7, 100) % 5).isZero();
        }

        @Test
        void rewards() {
            assertThat(ContractRule.reward(2000, 25)).isEqualTo(500);
            assertThat(ContractRule.reward(10, 25)).as("never less than 50").isEqualTo(50);
            assertThat(ContractRule.reward(Long.MAX_VALUE, 25)).isPositive();
        }

        @Test
        @DisplayName("a contract counts only what it asks for, stops at its goal, and is done once")
        void counting() {
            Contract iron = new Contract("elves", 1, 0, XpSource.MINE, "iron", "Mine iron ore", 10, 0, 100, false);
            assertThat(iron.counts(Activity.of(XpSource.MINE, "iron"))).isTrue();
            assertThat(iron.counts(Activity.of(XpSource.MINE, "coal"))).isFalse();
            assertThat(iron.counts(Activity.of(XpSource.FISH, 1))).isFalse();
            Contract nearly = iron.advanced(9);
            assertThat(nearly.done()).isFalse();
            Contract finished = nearly.advanced(5);
            assertThat(finished.done()).isTrue();
            assertThat(finished.progress()).isEqualTo(10);
            assertThat(finished.counts(Activity.of(XpSource.MINE, "iron"))).isFalse();
            Contract any = new Contract("elves", 1, 1, XpSource.MONSTER, "", "Kill monsters", 5, 0, 100, false);
            assertThat(any.counts(Activity.of(XpSource.MONSTER, "zombie"))).isTrue();
        }
    }
}
