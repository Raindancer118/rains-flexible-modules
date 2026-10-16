package de.raindancer.modules.guilds.rules;

import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.model.Relation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static de.raindancer.modules.guilds.model.Fixtures.MEMBER;
import static de.raindancer.modules.guilds.model.Fixtures.OFFICER;
import static de.raindancer.modules.guilds.model.Fixtures.RECRUIT;
import static de.raindancer.modules.guilds.model.Fixtures.STRANGER;
import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    @Nested
    @DisplayName("names and tags")
    class Names {

        private final GuildNameRule rule = new GuildNameRule(3, 24, 2, 5, List.of("badword"));

        @Test
        void fine() {
            assertThat(rule.name("The Elves of Lothlórien")).isEmpty();
            assertThat(rule.tag("ELF")).isEmpty();
        }

        @Test
        void lengths() {
            assertThat(rule.name("ab")).contains(GuildOutcome.NAME_TOO_SHORT);
            assertThat(rule.name("x".repeat(25))).contains(GuildOutcome.NAME_TOO_LONG);
            assertThat(rule.tag("E")).contains(GuildOutcome.TAG_TOO_SHORT);
            assertThat(rule.tag("ELVESS")).contains(GuildOutcome.TAG_TOO_LONG);
        }

        @ParameterizedTest
        @ValueSource(strings = {"<red>Elves", "Elves§c", " -Elves", "Elves!"})
        @DisplayName("markup, section signs and leading punctuation are not a name")
        void characters(String typed) {
            assertThat(rule.name(typed)).contains(GuildOutcome.NAME_CHARACTERS);
        }

        @ParameterizedTest
        @ValueSource(strings = {"E F", "E<F", "Ä1"})
        void tagCharacters(String typed) {
            assertThat(rule.tag(typed)).contains(GuildOutcome.TAG_CHARACTERS);
        }

        @ParameterizedTest
        @ValueSource(strings = {"The BadWord Guild", "B4dW0rd crew", "bädword"})
        @DisplayName("a banned word is found through capitals, digits and accents")
        void banned(String typed) {
            assertThat(rule.name(typed)).contains(GuildOutcome.NAME_BANNED);
        }

        @Test
        void tidy() {
            assertThat(GuildNameRule.tidy("  The   Elves ")).isEqualTo("The Elves");
        }
    }

    @Nested
    @DisplayName("ranks")
    class Ranks {

        private final RankRule rule = new RankRule();
        private final Guild guild = Fixtures.guild("Elves");

        @Test
        @DisplayName("an officer may kick those below, not the leader and not another officer")
        void kickDown() {
            assertThat(rule.mayActOn(guild, OFFICER, MEMBER, GuildPermission.KICK)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.mayActOn(guild, OFFICER, LEADER, GuildPermission.KICK)).isEqualTo(GuildOutcome.OUTRANKED);
            Guild twoOfficers = guild.withMember(guild.member(MEMBER).orElseThrow().withRank(Rank.OFFICER));
            assertThat(rule.mayActOn(twoOfficers, OFFICER, MEMBER, GuildPermission.KICK)).isEqualTo(GuildOutcome.OUTRANKED);
        }

        @Test
        void permissionAndMembership() {
            assertThat(rule.mayActOn(guild, MEMBER, RECRUIT, GuildPermission.KICK)).isEqualTo(GuildOutcome.NOT_ALLOWED);
            assertThat(rule.mayActOn(guild, STRANGER, RECRUIT, GuildPermission.KICK)).isEqualTo(GuildOutcome.NOT_A_MEMBER);
            assertThat(rule.mayActOn(guild, OFFICER, STRANGER, GuildPermission.KICK)).isEqualTo(GuildOutcome.NOT_A_MEMBER);
            assertThat(rule.mayActOn(guild, OFFICER, OFFICER, GuildPermission.KICK)).isEqualTo(GuildOutcome.YOURSELF);
        }

        @Test
        @DisplayName("an officer may promote a recruit to member, but never to officer — their own rank")
        void noPromotingToYourOwnRank() {
            Rank member = guild.rank(Rank.MEMBER).orElseThrow();
            Rank officer = guild.rank(Rank.OFFICER).orElseThrow();
            assertThat(rule.mayAssign(guild, OFFICER, RECRUIT, member)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.mayAssign(guild, OFFICER, RECRUIT, officer)).isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(rule.mayAssign(guild, LEADER, RECRUIT, officer)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.mayAssign(guild, LEADER, RECRUIT, guild.rank(Rank.LEADER).orElseThrow()))
                    .isEqualTo(GuildOutcome.LEADER_RANK);
        }

        @Test
        @DisplayName("nobody hands out a permission they do not hold")
        void grants() {
            Guild ranksForOfficers = guild.withRank(guild.rank(Rank.OFFICER).orElseThrow().toggled(GuildPermission.MANAGE_RANKS));
            Rank member = ranksForOfficers.rank(Rank.MEMBER).orElseThrow();
            assertThat(rule.mayGrant(ranksForOfficers, OFFICER, member, GuildPermission.KICK)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(rule.mayGrant(ranksForOfficers, OFFICER, member, GuildPermission.MANAGE_RANKS)).isEqualTo(GuildOutcome.SUCCESS);
            Guild withoutKick = ranksForOfficers.withRank(ranksForOfficers.rank(Rank.OFFICER).orElseThrow().toggled(GuildPermission.KICK));
            assertThat(rule.mayGrant(withoutKick, OFFICER, member, GuildPermission.KICK)).isEqualTo(GuildOutcome.PERMISSION_NOT_HELD);
            assertThat(rule.mayEdit(withoutKick, OFFICER, withoutKick.rank(Rank.OFFICER).orElseThrow())).isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(rule.mayEdit(guild, LEADER, guild.rank(Rank.LEADER).orElseThrow())).isEqualTo(GuildOutcome.LEADER_RANK);
        }

        @Test
        @DisplayName("promoting and demoting walk the ladder and stop at its ends, never reaching leader")
        void steps() {
            assertThat(rule.step(guild, RECRUIT, true)).map(Rank::id).contains(Rank.MEMBER);
            assertThat(rule.step(guild, RECRUIT, false)).isEmpty();
            assertThat(rule.step(guild, OFFICER, true)).isEmpty();
            assertThat(rule.step(guild, OFFICER, false)).map(Rank::id).contains(Rank.MEMBER);
        }

        @Test
        @DisplayName("the leader rank always holds everything, whatever was stored")
        void leaderIsLeader() {
            Rank stored = new Rank(Rank.LEADER, "Boss", 5, GuildPermission.none(), 10);
            assertThat(stored.weight()).isEqualTo(Rank.LEADER_WEIGHT);
            assertThat(stored.permissions()).containsExactlyInAnyOrderElementsOf(GuildPermission.all());
            assertThat(stored.isUnlimited()).isTrue();
        }

        @Test
        @DisplayName("a member whose rank was deleted counts as the lowest rank")
        void orphanedRank() {
            Guild orphan = guild.withMember(guild.member(MEMBER).orElseThrow().withRank("gone"));
            assertThat(orphan.rankOf(MEMBER)).map(Rank::id).contains(Rank.RECRUIT);
        }
    }

    @Nested
    @DisplayName("levels")
    class Levels {

        private final LevelRule rule = new LevelRule(1000, 1.5, 20, 10, 2, 40, 1, 3, 2);

        @Test
        void curve() {
            assertThat(rule.toReach(1)).isZero();
            assertThat(rule.toReach(2)).isEqualTo(1000);
            assertThat(rule.toReach(3)).isEqualTo(Math.round(1000 * Math.pow(2, 1.5)));
            assertThat(rule.levelOf(0)).isEqualTo(1);
            assertThat(rule.levelOf(999)).isEqualTo(1);
            assertThat(rule.levelOf(1000)).isEqualTo(2);
            assertThat(rule.levelOf(Long.MAX_VALUE)).isEqualTo(20);
        }

        @Test
        @DisplayName("every level costs more than the last")
        void increasing() {
            long before = 0;
            for (int level = 2; level <= 20; level++) {
                long step = rule.toReach(level) - rule.toReach(level - 1);
                assertThat(step).as("level %d", level).isGreaterThanOrEqualTo(before);
                before = step;
            }
        }

        @Test
        void perks() {
            assertThat(rule.memberCap(1)).isEqualTo(10);
            assertThat(rule.memberCap(5)).isEqualTo(18);
            assertThat(rule.memberCap(20)).isEqualTo(40);
            assertThat(rule.allySlots(1)).isEqualTo(1);
            assertThat(rule.allySlots(4)).isEqualTo(2);
            assertThat(rule.hasHome(1)).isFalse();
            assertThat(rule.hasHome(2)).isTrue();
        }

        @Test
        void progress() {
            assertThat(rule.progress(500)).isEqualTo(0.5);
            assertThat(rule.progress(Long.MAX_VALUE)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("friendly fire")
    class FriendlyFire {

        private final FriendlyFireRule rule = new FriendlyFireRule();
        private final Guild elves = Fixtures.solo("Elves", Fixtures.player("a"));
        private final Guild dwarves = Fixtures.solo("Dwarves", Fixtures.player("b"));

        @Test
        void sameGuild() {
            assertThat(rule.judge(Optional.of(elves), Optional.of(elves))).isEqualTo(Verdict.PROTECTED);
            Guild brawlers = elves.withFriendlyFire(true, false);
            assertThat(rule.judge(Optional.of(brawlers), Optional.of(brawlers))).isNull();
        }

        @Test
        void strangersAndGuildless() {
            assertThat(rule.judge(Optional.of(elves), Optional.of(dwarves))).isNull();
            assertThat(rule.judge(Optional.empty(), Optional.of(dwarves))).isNull();
            assertThat(rule.judge(Optional.of(elves), Optional.empty())).isNull();
        }

        @Test
        @DisplayName("allies are protected only when both sides hold the alliance")
        void allies() {
            Guild elvesAllied = elves.withRelation(dwarves.id(), Relation.ALLY);
            Guild dwarvesAllied = dwarves.withRelation(elves.id(), Relation.ALLY);
            assertThat(rule.judge(Optional.of(elvesAllied), Optional.of(dwarvesAllied))).isEqualTo(Verdict.PROTECTED);
            assertThat(rule.judge(Optional.of(elvesAllied), Optional.of(dwarves))).isNull();
            assertThat(rule.judge(Optional.of(elvesAllied.withFriendlyFire(false, true)), Optional.of(dwarvesAllied))).isNull();
        }
    }

    @Nested
    @DisplayName("succession")
    class Succession {

        private final SuccessionRule rule = new SuccessionRule();
        private final Guild guild = Fixtures.guild("Elves");
        private static final long DAY = Duration.ofDays(1).toMillis();
        private final long now = 100 * DAY;

        private java.util.function.Function<UUID, OptionalLong> seen(Map<UUID, Long> when) {
            return who -> when.containsKey(who) ? OptionalLong.of(when.get(who)) : OptionalLong.empty();
        }

        @Test
        @DisplayName("an active leader stays; a gone one is replaced by the most senior active member")
        void heir() {
            Map<UUID, Long> when = new HashMap<>(Map.of(LEADER, now - DAY, OFFICER, now - DAY, MEMBER, now));
            assertThat(rule.heir(guild, seen(when), now, Duration.ofDays(30))).isEmpty();
            when.put(LEADER, now - 40 * DAY);
            assertThat(rule.heir(guild, seen(when), now, Duration.ofDays(30))).contains(OFFICER);
            when.put(OFFICER, now - 31 * DAY);
            assertThat(rule.heir(guild, seen(when), now, Duration.ofDays(30))).contains(MEMBER);
        }

        @Test
        @DisplayName("a leader nobody knows about is never replaced, and zero means never")
        void unknownOrOff() {
            Map<UUID, Long> when = Map.of(OFFICER, now);
            assertThat(rule.heir(guild, seen(when), now, Duration.ofDays(30))).isEmpty();
            assertThat(rule.heir(guild, seen(Map.of(LEADER, 0L, OFFICER, now)), now, Duration.ZERO)).isEmpty();
        }

        @Test
        @DisplayName("among equals the longest-serving wins")
        void seniority() {
            UUID late = Fixtures.player("late");
            Guild two = guild.withMember(new Member(late, Rank.OFFICER, 0, 0, 0, 0, 0, 0));
            Map<UUID, Long> when = Map.of(LEADER, 0L, OFFICER, now, late, now);
            assertThat(rule.heir(two, seen(when), now, Duration.ofDays(30))).contains(late);
        }
    }

    @Nested
    @DisplayName("sharing a disbanded bank")
    class Sharing {

        private final ShareRule rule = new ShareRule();

        @Test
        void byContribution() {
            Map<UUID, Long> put = new LinkedHashMap<>();
            put.put(OFFICER, 300L);
            put.put(MEMBER, 100L);
            assertThat(rule.share(1000, ShareRule.Mode.CONTRIBUTION, put, LEADER))
                    .containsEntry(OFFICER, 750L).containsEntry(MEMBER, 250L).hasSize(2);
        }

        @Test
        void leaderAndEven() {
            Map<UUID, Long> put = Map.of(OFFICER, 300L, MEMBER, 100L, RECRUIT, 0L);
            assertThat(rule.share(1000, ShareRule.Mode.LEADER, put, LEADER)).containsExactly(Map.entry(LEADER, 1000L));
            Map<UUID, Long> even = rule.share(1000, ShareRule.Mode.EVEN, put, LEADER);
            assertThat(even.values()).containsExactlyInAnyOrder(334L, 333L, 333L);
        }

        @Test
        @DisplayName("nobody put anything in: shared evenly between the members named")
        void noClaims() {
            Map<UUID, Long> put = Map.of(OFFICER, 0L, MEMBER, 0L);
            assertThat(rule.share(10, ShareRule.Mode.CONTRIBUTION, put, LEADER).values()).containsExactlyInAnyOrder(5L, 5L);
        }

        @Test
        @DisplayName("the shares always add up to exactly the pot")
        void exact() {
            Random random = new Random(118);
            for (int round = 0; round < 2000; round++) {
                Map<UUID, Long> put = new LinkedHashMap<>();
                int members = 1 + random.nextInt(30);
                for (int each = 0; each < members; each++) {
                    put.put(UUID.randomUUID(), (long) random.nextInt(1_000_000));
                }
                long pot = random.nextLong(1, Long.MAX_VALUE / 4);
                for (ShareRule.Mode mode : ShareRule.Mode.values()) {
                    Map<UUID, Long> shares = rule.share(pot, mode, put, LEADER);
                    assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).as("%s round %d", mode, round).isEqualTo(pot);
                    assertThat(shares.values()).allMatch(amount -> amount > 0);
                }
            }
        }

        @Test
        void empty() {
            assertThat(rule.share(0, ShareRule.Mode.EVEN, Map.of(MEMBER, 1L), LEADER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("withdrawing")
    class Withdrawing {

        private final WithdrawRule rule = new WithdrawRule();

        @Test
        void dailyLimit() {
            Rank member = new Rank(Rank.MEMBER, "Member", 200, GuildPermission.none(), 100);
            Member fresh = Member.joining(MEMBER, Rank.MEMBER, 0);
            assertThat(rule.mayTake(member, fresh, 10, 1000)).isEqualTo(100);
            Member took = fresh.withdrawing(60, 10);
            assertThat(rule.mayTake(member, took, 10, 1000)).isEqualTo(40);
            assertThat(rule.mayTake(member, took, 11, 1000)).as("a new day").isEqualTo(100);
            assertThat(rule.mayTake(member, fresh, 10, 30)).as("only what is there").isEqualTo(30);
            assertThat(rule.mayTake(new Rank(Rank.LEADER, "L", 0, null, 0), fresh, 10, 5000)).isEqualTo(5000);
        }

        @Test
        @DisplayName("a member's own limit wins over the rank's, either way; without one the rank's applies")
        void ownLimit() {
            Rank member = new Rank(Rank.MEMBER, "Member", 200, GuildPermission.none(), 100);
            Member fresh = Member.joining(MEMBER, Rank.MEMBER, 0);
            assertThat(rule.limit(member, fresh)).isEqualTo(100);
            assertThat(rule.limit(member, fresh.withOwnLimit(5000))).isEqualTo(5000);
            assertThat(rule.mayTake(member, fresh.withOwnLimit(5000), 10, 9000)).as("above the rank's").isEqualTo(5000);
            assertThat(rule.mayTake(member, fresh.withOwnLimit(20), 10, 9000)).as("below the rank's").isEqualTo(20);
            assertThat(rule.mayTake(member, fresh.withOwnLimit(-1), 10, 9000)).as("none at all").isEqualTo(9000);
            assertThat(rule.mayTake(member, fresh.withOwnLimit(Member.RANK_LIMIT), 10, 9000)).as("back to the rank's")
                    .isEqualTo(100);
            Rank leader = new Rank(Rank.LEADER, "L", 0, null, 0);
            assertThat(rule.limit(leader, fresh.withOwnLimit(10))).as("nobody limits the leader").isEqualTo(-1);
        }

        @Test
        @DisplayName("experience earned keeps when the member was last seen")
        void earningKeepsSeen() {
            Member seen = Member.joining(MEMBER, Rank.MEMBER, 500);
            assertThat(seen.earning(3).seen()).isEqualTo(500);
            assertThat(seen.withOwnLimit(7).earning(3).ownLimit()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("experience")
    class Experience {

        @Test
        void capAndDamping() {
            XpRule rule = new XpRule(100, 0);
            assertThat(rule.award(30, 0, 1)).isEqualTo(30);
            assertThat(rule.award(30, 90, 1)).isEqualTo(10);
            assertThat(rule.award(30, 100, 1)).isZero();
            assertThat(rule.award(0, 0, 1)).isZero();
            XpRule damped = new XpRule(0, 0.5);
            assertThat(damped.award(100, 0, 1)).isEqualTo(100);
            assertThat(damped.award(100, 0, 20)).isLessThan(100).isGreaterThan(30);
            assertThat(damped.award(1, 0, 50)).as("never rounded down to nothing").isEqualTo(1);
        }
    }
}
