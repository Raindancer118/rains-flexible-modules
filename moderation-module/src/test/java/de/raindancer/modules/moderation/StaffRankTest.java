package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.rules.StaffRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four tiers ops can promote somebody to.
 *
 * <h2>Why presets at all, when every node is toggleable anyway</h2>
 * Because "which of these thirteen nodes does a helper get?" is a question nobody should have to answer
 * twice, and the server that answers it differently each time is the server where one helper can ban
 * and another cannot, for no reason either of them knows. The preset is the answer written down once;
 * the per-person toggles are for the exception.
 *
 * <h2>The one property that matters most</h2>
 * Each tier contains the one below it. Not tidiness — it is what makes a promotion a promotion: if
 * Moderator lacked something Helper had, promoting somebody would silently take a power away, and the
 * person who lost it would report it as a bug in whatever they were trying to do at the time.
 */
class StaffRankTest {

    @Nested
    @DisplayName("the ladder")
    class TheLadder {

        @Test
        @DisplayName("there are four, in the order ops climb them")
        void fourOfThem() {
            assertThat(StaffRank.values())
                    .containsExactly(StaffRank.TRIAL, StaffRank.HELPER, StaffRank.MODERATOR,
                            StaffRank.ADMIN);
        }

        @Test
        @DisplayName("each tier contains everything the one below it has")
        void eachContainsTheLast() {
            // The property the whole scheme rests on: a promotion never takes a power away.
            StaffRank[] ladder = StaffRank.values();
            for (int rung = 1; rung < ladder.length; rung++) {
                assertThat(ladder[rung].nodes())
                        .as("%s should contain everything %s has", ladder[rung], ladder[rung - 1])
                        .containsAll(ladder[rung - 1].nodes());
            }
        }

        @Test
        @DisplayName("each tier is strictly bigger than the one below")
        void eachIsBigger() {
            // Two tiers with the same nodes are two names for one thing, and the difference between
            // them is then whatever somebody imagines it to be.
            StaffRank[] ladder = StaffRank.values();
            for (int rung = 1; rung < ladder.length; rung++) {
                assertThat(ladder[rung].nodes().size())
                        .as("%s adds nothing to %s", ladder[rung], ladder[rung - 1])
                        .isGreaterThan(ladder[rung - 1].nodes().size());
            }
        }

        @Test
        @DisplayName("they are ordered, so two can be compared")
        void ordered() {
            assertThat(StaffRank.TRIAL.weight()).isLessThan(StaffRank.HELPER.weight());
            assertThat(StaffRank.HELPER.weight()).isLessThan(StaffRank.MODERATOR.weight());
            assertThat(StaffRank.MODERATOR.weight()).isLessThan(StaffRank.ADMIN.weight());
            assertThat(StaffRank.ADMIN.isAtLeast(StaffRank.HELPER)).isTrue();
            assertThat(StaffRank.HELPER.isAtLeast(StaffRank.ADMIN)).isFalse();
            assertThat(StaffRank.HELPER.isAtLeast(StaffRank.HELPER)).isTrue();
        }

        @Test
        @DisplayName("every tier says what it is and what it is for")
        void described() {
            for (StaffRank rank : StaffRank.values()) {
                assertThat(rank.title()).isNotBlank();
                assertThat(rank.describe()).isNotBlank();
                assertThat(rank.icon()).isNotNull();
                assertThat(rank.colour()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("what each tier may do")
    class WhatTheyGet {

        @Test
        @DisplayName("a trial can be useful and watched, and stops nobody doing anything")
        void trialIsTiny() {
            Set<String> theirs = StaffRank.TRIAL.nodes();

            assertThat(theirs).contains(ModerationPermission.WARN.node(),
                    ModerationPermission.HISTORY.node(), ModerationPermission.STAFF_CHAT.node());
            // Nothing that takes anything away from a player. A trial who can ban is not a trial.
            assertThat(theirs).doesNotContain(ModerationPermission.BAN.node(),
                    ModerationPermission.MUTE.node(), ModerationPermission.KICK.node());
        }

        @Test
        @DisplayName("a helper can quiet somebody but not remove them")
        void helperStopsShortOfBanning() {
            Set<String> theirs = StaffRank.HELPER.nodes();

            assertThat(theirs).contains(ModerationPermission.MUTE.node(),
                    ModerationPermission.KICK.node(), ModerationPermission.REPORTS.node(),
                    ModerationPermission.INVSEE.node());
            assertThat(theirs)
                    .as("the whole reason mute and ban are separate nodes")
                    .doesNotContain(ModerationPermission.BAN.node());
            assertThat(theirs)
                    .as("looking in an inventory is not the same power as emptying it")
                    .doesNotContain(ModerationPermission.INVSEE_EDIT.node());
        }

        @Test
        @DisplayName("a moderator can ban, freeze, vanish and read the notes")
        void moderatorIsTheFullSet() {
            Set<String> theirs = StaffRank.MODERATOR.nodes();

            assertThat(theirs).contains(ModerationPermission.BAN.node(),
                    ModerationPermission.FREEZE.node(), ModerationPermission.VANISH.node(),
                    ModerationPermission.NOTES.node(), ModerationPermission.INVSEE_EDIT.node());
        }

        @Test
        @DisplayName("only an admin may change how moderation itself behaves")
        void configIsAdminOnly() {
            // Somebody who can set warns-before-ban to zero can undo the server's own policy, which is
            // a different kind of power from handing out a punishment under it.
            assertThat(StaffRank.ADMIN.nodes()).contains(ModerationPermission.CONFIG.node());
            assertThat(StaffRank.MODERATOR.nodes())
                    .doesNotContain(ModerationPermission.CONFIG.node());
        }

        @Test
        @DisplayName("only an admin is immune")
        void immunityIsAdminOnly() {
            assertThat(StaffRank.ADMIN.nodes()).contains(StaffRule.IMMUNE);
            assertThat(StaffRank.MODERATOR.nodes()).doesNotContain(StaffRule.IMMUNE);
        }
    }

    @Nested
    @DisplayName("the claims half")
    class Claims {

        @Test
        @DisplayName("a moderator can act on claims while moderating")
        void moderatorsGetClaimAdmin() {
            assertThat(StaffRank.MODERATOR.nodes()).contains(StaffRank.CLAIM_ADMIN);
        }

        @Test
        @DisplayName("the bypasses arrive with admin, not before")
        void bypassesAreAdminOnly() {
            // A moderator with free unlimited claims is a moderator whose own building is invisible to
            // the rules everybody else plays under.
            for (String bypass : StaffRank.CLAIM_BYPASSES) {
                assertThat(StaffRank.ADMIN.nodes())
                        .as("an admin should have %s", bypass)
                        .contains(bypass);
                assertThat(StaffRank.MODERATOR.nodes())
                        .as("a moderator should not have %s", bypass)
                        .doesNotContain(bypass);
            }
        }

        @Test
        @DisplayName("nobody is granted the ability to use claims, because everybody already can")
        void noPointlessGrants() {
            // rec.use is on by default for every player. Granting it would be a node in the file that
            // changes nothing, and the first person to read the file would wonder what it was for.
            for (StaffRank rank : StaffRank.values()) {
                assertThat(rank.nodes()).doesNotContain("rec.use");
            }
        }
    }

    @Nested
    @DisplayName("reading one back")
    class Parsing {

        @Test
        @DisplayName("a rank can be named however it is typed")
        void byName() {
            assertThat(StaffRank.byName("moderator")).contains(StaffRank.MODERATOR);
            assertThat(StaffRank.byName("MODERATOR")).contains(StaffRank.MODERATOR);
            assertThat(StaffRank.byName("  Helper ")).contains(StaffRank.HELPER);
            assertThat(StaffRank.byName("nonsense")).isEmpty();
            assertThat(StaffRank.byName(null)).isEmpty();
            assertThat(StaffRank.byName("")).isEmpty();
        }

        @Test
        @DisplayName("the names are what a moderator would type, for tab completion")
        void names() {
            List<String> names = new ArrayList<>(StaffRank.names());

            assertThat(names).containsExactly("trial", "helper", "moderator", "admin");
        }
    }

    @Test
    @DisplayName("no node is granted twice within one tier")
    void noDuplicates() {
        for (StaffRank rank : StaffRank.values()) {
            assertThat(rank.nodes()).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("the set handed out cannot be changed by whoever reads it")
    void nodesAreImmutable() {
        // A preset that a caller can add to is a preset that means something different after the first
        // menu has rendered.
        Set<String> theirs = StaffRank.HELPER.nodes();

        assertThat(theirs).isUnmodifiable();
    }
}
