package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerkRulesTest {

    private final PerkRule rule = new PerkRule(3, 1, Duration.ofDays(7));

    @Nested
    @DisplayName("points")
    class Points {

        @Test
        @DisplayName("levels, prestige and crowns all give points; ranks spend them")
        void earnedAndSpent() {
            Standing standing = new Standing(4, 2, 1, Map.of(Perk.VAULT, 2), 0);
            assertThat(rule.earned(standing)).isEqualTo(4 + 6 + 1);
            assertThat(rule.unspent(standing)).isEqualTo(9);
            assertThat(rule.unspent(new Standing(1, 0, 0, Map.of(Perk.VAULT, 3), 0))).as("never below zero").isZero();
        }

        @Test
        @DisplayName("a rank stored above its perk's top is clamped on the way in")
        void clamped() {
            assertThat(new Standing(0, 0, 0, Map.of(Perk.HASTE, 9), 0).rank(Perk.HASTE)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("buying")
    class Buying {

        @Test
        @DisplayName("a rank needs a point, its level, and room at the top")
        void raise() {
            Standing poor = Standing.NONE;
            assertThat(rule.mayRaise(poor, Perk.VAULT, 10)).isEqualTo(GuildOutcome.NO_PERK_POINTS);
            Standing rich = new Standing(20, 0, 0, Map.of(), 0);
            assertThat(rule.mayRaise(rich, Perk.VAULT, 1)).as("vault I needs level 2").isEqualTo(GuildOutcome.LEVEL_TOO_LOW);
            assertThat(rule.mayRaise(rich, Perk.VAULT, 2)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.mayRaise(rich.withPerk(Perk.VAULT, 1), Perk.VAULT, 4)).as("vault II needs level 5").isEqualTo(GuildOutcome.LEVEL_TOO_LOW);
            assertThat(rule.mayRaise(rich.withPerk(Perk.HASTE, 2), Perk.HASTE, 25)).isEqualTo(GuildOutcome.PERK_MAXED);
        }

        @Test
        void levels() {
            assertThat(Perk.VAULT.levelFor(1)).isEqualTo(2);
            assertThat(Perk.VAULT.levelFor(6)).isEqualTo(17);
            assertThat(Perk.HASTE.levelFor(2)).isEqualTo(12);
            for (Perk perk : Perk.values()) {
                assertThat(perk.levelFor(perk.maxRank())).as("%s is reachable by the default top level", perk).isLessThanOrEqualTo(25);
            }
        }

        @Test
        @DisplayName("points come back once a week, and only when something was bought")
        void respec() {
            long day = Duration.ofDays(1).toMillis();
            assertThat(rule.mayRespec(Standing.NONE, 0)).isEqualTo(GuildOutcome.NOTHING_TO_RESPEC);
            Standing bought = new Standing(5, 0, 0, Map.of(Perk.SCHOLAR, 1), 0);
            assertThat(rule.mayRespec(bought, 10 * day)).isEqualTo(GuildOutcome.SUCCESS);
            Standing after = bought.respecced(10 * day).withPerk(Perk.SCHOLAR, 1);
            assertThat(after.perks()).containsOnlyKeys(Perk.SCHOLAR);
            assertThat(rule.mayRespec(after, 16 * day)).isEqualTo(GuildOutcome.ON_COOLDOWN);
            assertThat(rule.mayRespec(after, 17 * day)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.respecAgainAt(after)).isEqualTo(17 * day);
        }
    }

    @Nested
    @DisplayName("what perks do")
    class Effects {

        @Test
        void effects() {
            Standing all = new Standing(0, 2, 0, Map.of(Perk.VAULT, 3, Perk.HASTE, 2, Perk.LUCK, 1, Perk.SCHOLAR, 3,
                    Perk.PROSPEROUS, 2, Perk.RECRUITER, 3, Perk.DIPLOMAT, 1, Perk.PATHFINDER, 1), 0);
            assertThat(PerkRule.vaultSlots(all)).isEqualTo(27);
            assertThat(PerkRule.hasteAmplifier(all)).isEqualTo(1);
            assertThat(PerkRule.luckAmplifier(all)).isZero();
            assertThat(PerkRule.hasteAmplifier(Standing.NONE)).as("none").isEqualTo(-1);
            assertThat(PerkRule.vanillaXpPercent(all)).isEqualTo(30);
            assertThat(PerkRule.guildXpPercent(all, 5)).as("10% prosperous + 2 prestige × 5%").isEqualTo(20);
            assertThat(PerkRule.extraMembers(all)).isEqualTo(6);
            assertThat(PerkRule.extraAllies(all)).isEqualTo(1);
            assertThat(PerkRule.homeTravelPercent(all)).isEqualTo(50);
            assertThat(PerkRule.homeTravelPercent(Standing.NONE)).isEqualTo(100);
            assertThat(PerkRule.stars(0)).isEmpty();
            assertThat(PerkRule.stars(1)).isEqualTo("★");
            assertThat(PerkRule.stars(3)).isEqualTo("★3");
        }
    }

    @Nested
    @DisplayName("the crown")
    class Crown {

        @Test
        @DisplayName("most experience first, ties by id, nobody without experience")
        void podium() {
            CrownRule crown = new CrownRule();
            var podium = crown.podium(Map.of("elves", 500L, "dwarves", 900L, "orcs", 500L, "idle", 0L), 3);
            assertThat(podium).extracting(CrownRule.Place::guild).containsExactly("dwarves", "elves", "orcs");
            assertThat(crown.podium(Map.of("idle", 0L), 3)).isEmpty();
        }
    }
}
