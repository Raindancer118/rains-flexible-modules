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
 * The four ranks: trial mod, mod, admin, owner.
 *
 * <h2>Why presets at all, when every node is toggleable anyway</h2>
 * Because "which of these nodes does a trial mod get?" is a question nobody should have to answer twice,
 * and the server that answers it afresh each time is the server where one trial can ban and another
 * cannot for no reason either of them knows. The preset is the answer written down once; the per-person
 * toggles are for the exception.
 *
 * <h2>The property the scheme rests on</h2>
 * Each rank contains the one below it. Not tidiness — it is what makes a promotion a promotion: a rank
 * missing something the rank under it had would silently take a power away, and the person who lost it
 * would report it as a bug in whatever they happened to be doing.
 *
 * <p>The owner is the exception, and deliberately: it adds no <em>nodes</em>, because an operator already
 * holds every permission of every plugin. Granting nodes on top would be a list in a file that changes
 * nothing, and the first person to read it would reasonably conclude that op does not include them.
 */
class StaffRankTest {

    /** The three that are staff-by-permission. The owner is staff-by-being-the-server. */
    private static final List<StaffRank> WORKING_RANKS =
            List.of(StaffRank.TRIAL_MOD, StaffRank.MOD, StaffRank.ADMIN);

    @Nested
    @DisplayName("the ladder")
    class TheLadder {

        @Test
        @DisplayName("there are four, in the order they are climbed")
        void fourOfThem() {
            assertThat(StaffRank.values())
                    .containsExactly(StaffRank.TRIAL_MOD, StaffRank.MOD, StaffRank.ADMIN,
                            StaffRank.OWNER);
        }

        @Test
        @DisplayName("each rank contains everything the one below it has")
        void eachContainsTheLast() {
            StaffRank[] ladder = StaffRank.values();
            for (int rung = 1; rung < ladder.length; rung++) {
                assertThat(ladder[rung].nodes())
                        .as("%s should contain everything %s has", ladder[rung], ladder[rung - 1])
                        .containsAll(ladder[rung - 1].nodes());
            }
        }

        @Test
        @DisplayName("each working rank is strictly bigger than the one below")
        void eachIsBigger() {
            // Two ranks with the same nodes are two names for one thing, and the difference between them
            // is then whatever somebody imagines it to be. The owner is exempt: it is bigger in power
            // without being bigger in nodes, which is what op *is*.
            for (int rung = 1; rung < WORKING_RANKS.size(); rung++) {
                assertThat(WORKING_RANKS.get(rung).nodes().size())
                        .as("%s adds nothing to %s", WORKING_RANKS.get(rung),
                                WORKING_RANKS.get(rung - 1))
                        .isGreaterThan(WORKING_RANKS.get(rung - 1).nodes().size());
            }
        }

        @Test
        @DisplayName("they are ordered, so two can be compared")
        void ordered() {
            assertThat(StaffRank.TRIAL_MOD.weight()).isLessThan(StaffRank.MOD.weight());
            assertThat(StaffRank.MOD.weight()).isLessThan(StaffRank.ADMIN.weight());
            assertThat(StaffRank.ADMIN.weight()).isLessThan(StaffRank.OWNER.weight());

            assertThat(StaffRank.ADMIN.isAtLeast(StaffRank.MOD)).isTrue();
            assertThat(StaffRank.MOD.isAtLeast(StaffRank.ADMIN)).isFalse();
            assertThat(StaffRank.MOD.isAtLeast(StaffRank.MOD)).isTrue();
        }

        @Test
        @DisplayName("each one knows the rung above and below it")
        void neighbours() {
            // What a promotion and a demotion by one step are built on, and what decides which rank
            // somebody may hand out.
            assertThat(StaffRank.TRIAL_MOD.below()).isEmpty();
            assertThat(StaffRank.TRIAL_MOD.above()).contains(StaffRank.MOD);
            assertThat(StaffRank.MOD.below()).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.OWNER.above()).isEmpty();
            assertThat(StaffRank.OWNER.below()).contains(StaffRank.ADMIN);
        }

        @Test
        @DisplayName("every rank says what it is and what it is for")
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
    @DisplayName("what each rank may do")
    class WhatTheyGet {

        @Test
        @DisplayName("a trial mod can see everything and change almost nothing")
        void aTrialCanRead() {
            // Deliberately generous about reading: a trial who cannot see a record or a report cannot
            // learn the job, and being useful while watched is the whole point of the rank.
            Set<String> theirs = StaffRank.TRIAL_MOD.nodes();

            assertThat(theirs).contains(ModerationPermission.WARN.node(),
                    ModerationPermission.HISTORY.node(), ModerationPermission.STAFF_CHAT.node(),
                    ModerationPermission.REPORTS.node(), ModerationPermission.INVSEE.node());

            // Nothing that takes anything away from a player, and nothing that changes an inventory.
            assertThat(theirs).doesNotContain(ModerationPermission.BAN.node(),
                    ModerationPermission.MUTE.node(), ModerationPermission.KICK.node(),
                    ModerationPermission.INVSEE_EDIT.node());
        }

        @Test
        @DisplayName("a mod has the full working set")
        void aModCanAct() {
            Set<String> theirs = StaffRank.MOD.nodes();

            assertThat(theirs).contains(ModerationPermission.TEMPBAN.node(),
                    ModerationPermission.MUTE.node(), ModerationPermission.KICK.node(),
                    ModerationPermission.FREEZE.node(), ModerationPermission.VANISH.node(),
                    ModerationPermission.NOTES.node(), ModerationPermission.INVSEE_EDIT.node());

            // A mod stops somebody *now*; ending their time on the server for good is an admin's.
            assertThat(theirs)
                    .as("a permanent ban is not a mod's decision — see BanLimitRule")
                    .doesNotContain(ModerationPermission.BAN.node());
        }

        @Test
        @DisplayName("only an admin may change how moderation itself behaves")
        void configIsAdminUpward() {
            // Somebody who can set warns-before-ban to zero can undo the server's own policy, which is
            // a different kind of power from handing out a punishment under it.
            assertThat(StaffRank.ADMIN.nodes()).contains(ModerationPermission.CONFIG.node());
            assertThat(StaffRank.MOD.nodes()).doesNotContain(ModerationPermission.CONFIG.node());
        }

        @Test
        @DisplayName("no rank grants protection, at any tier")
        void protectionIsNeverGranted() {
            // A promotion used to confer it. That made the shield that stops one moderator acting on
            // another something the moderators could hand to each other — so it is now written only by
            // /protect at the console, and no tier here may put it back.
            for (StaffRank rank : StaffRank.values()) {
                assertThat(rank.nodes())
                        .as("%s grants protection", rank)
                        .noneMatch(node -> node.endsWith(".immune"));
            }
        }
    }

    @Nested
    @DisplayName("the owner")
    class Owner {

        @Test
        @DisplayName("the owner is the only rank that is an operator")
        void onlyTheOwnerIsOp() {
            assertThat(StaffRank.OWNER.isOperator()).isTrue();
            for (StaffRank rank : WORKING_RANKS) {
                assertThat(rank.isOperator())
                        .as("%s must not be an operator — op is every permission of every plugin", rank)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the owner adds no nodes, because an operator already holds everything")
        void theOwnerAddsNoNodes() {
            assertThat(StaffRank.OWNER.nodes())
                    .as("a node list on top of op is a list that changes nothing, and reads as though "
                            + "op did not include it")
                    .isEqualTo(StaffRank.ADMIN.nodes());
        }
    }

    @Nested
    @DisplayName("the claims half")
    class Claims {

        @Test
        @DisplayName("a mod can act on claims while moderating")
        void modsGetClaimAdmin() {
            assertThat(StaffRank.MOD.nodes()).contains(StaffRank.CLAIM_ADMIN);
            assertThat(StaffRank.TRIAL_MOD.nodes()).doesNotContain(StaffRank.CLAIM_ADMIN);
        }

        @Test
        @DisplayName("the bypasses arrive with admin, not before")
        void bypassesAreAdminUpward() {
            // A mod with free unlimited claims is a mod whose own building is invisible to the rules
            // everybody else plays under — and the first thing a player notices when they find out.
            for (String bypass : StaffRank.CLAIM_BYPASSES) {
                assertThat(StaffRank.ADMIN.nodes())
                        .as("an admin should have %s", bypass)
                        .contains(bypass);
                assertThat(StaffRank.MOD.nodes())
                        .as("a mod should not have %s", bypass)
                        .doesNotContain(bypass);
            }
        }

        @Test
        @DisplayName("nobody is granted the ability to use claims, because everybody already can")
        void noPointlessGrants() {
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
            assertThat(StaffRank.byName("mod")).contains(StaffRank.MOD);
            assertThat(StaffRank.byName("MOD")).contains(StaffRank.MOD);
            assertThat(StaffRank.byName("  Admin ")).contains(StaffRank.ADMIN);
            assertThat(StaffRank.byName("trial_mod")).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.byName("nonsense")).isEmpty();
            assertThat(StaffRank.byName(null)).isEmpty();
            assertThat(StaffRank.byName("")).isEmpty();
        }

        @Test
        @DisplayName("the words people actually type are understood too")
        void theWordsPeopleUse() {
            // Nobody types an underscore, and "op" is what the top rank is called in practice even
            // though it is the owner.
            assertThat(StaffRank.byName("trial")).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.byName("trialmod")).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.byName("trial mod")).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.byName("trial-mod")).contains(StaffRank.TRIAL_MOD);
            assertThat(StaffRank.byName("moderator")).contains(StaffRank.MOD);
            assertThat(StaffRank.byName("op")).contains(StaffRank.OWNER);
            assertThat(StaffRank.byName("owner")).contains(StaffRank.OWNER);
        }

        @Test
        @DisplayName("the names are what somebody would type, for tab completion")
        void names() {
            assertThat(new ArrayList<>(StaffRank.names()))
                    .containsExactly("trial_mod", "mod", "admin", "owner");
        }
    }

    @Test
    @DisplayName("no node is granted twice within one rank")
    void noDuplicates() {
        for (StaffRank rank : StaffRank.values()) {
            assertThat(rank.nodes()).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("the set handed out cannot be changed by whoever reads it")
    void nodesAreImmutable() {
        assertThat(StaffRank.MOD.nodes()).isUnmodifiable();
    }
}
