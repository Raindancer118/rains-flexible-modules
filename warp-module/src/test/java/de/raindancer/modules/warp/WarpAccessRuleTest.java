package de.raindancer.modules.warp;

import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may use which warp, and who may change one.
 *
 * <p>This is the security decision of the whole module, so it is deliberately a value-in,
 * value-out rule with no server anywhere near it. A rule that needed a {@code Player} would be one
 * checked by hand on a test server, and "the staff warps are visible to everybody" is not a thing to
 * find out that way.
 */
class WarpAccessRuleTest {

    private final WarpAccessRule rule = new WarpAccessRule();

    private static Predicate<String> holding(String... nodes) {
        Set<String> held = Set.of(nodes);
        return held::contains;
    }

    /** Somebody with nothing granted at all. */
    private static final Predicate<String> NOBODY = holding();

    /** An ordinary player: they may warp, and nothing else. */
    private static final Predicate<String> PLAYER = holding(PermissionNodes.USE);

    /** Staff: they may warp and they hold the staff node. */
    private static final Predicate<String> STAFF =
            holding(PermissionNodes.USE, WarpAccess.STAFF_PERMISSION);

    /** An admin: they manage warps. */
    private static final Predicate<String> ADMIN =
            holding(PermissionNodes.USE, PermissionNodes.MANAGE);

    @Nested
    @DisplayName("using one")
    class Using {

        @Test
        @DisplayName("a public warp is for anybody who may warp at all")
        void publicWarps() {
            assertThat(rule.mayUse(WarpAccess.EVERYONE, PLAYER)).isTrue();
        }

        @Test
        @DisplayName("somebody who may not warp at all reaches nothing")
        void warpingItselfCanBeTakenAway() {
            // The node that switches the feature off for a group. Without this check a server that
            // removed rainswarps.warp.use would find every public warp still working.
            assertThat(rule.mayUse(WarpAccess.EVERYONE, NOBODY)).isFalse();
        }

        @Test
        @DisplayName("a staff warp is not for an ordinary player")
        void staffWarpsAreNotPublic() {
            assertThat(rule.mayUse(WarpAccess.STAFF, PLAYER)).isFalse();
            assertThat(rule.mayUse(WarpAccess.STAFF, STAFF)).isTrue();
        }

        @Test
        @DisplayName("a warp with its own permission needs exactly that permission")
        void ownPermission() {
            WarpAccess build = new WarpAccess.Needing("rainswarps.warp.build");

            assertThat(rule.mayUse(build, PLAYER)).isFalse();
            assertThat(rule.mayUse(build, STAFF))
                    .as("staff and builders are different groups on every server that has both")
                    .isFalse();
            assertThat(rule.mayUse(build, holding(PermissionNodes.USE, "rainswarps.warp.build")))
                    .isTrue();
        }

        @Test
        @DisplayName("an admin reaches every warp, including ones they were never granted")
        void adminsReachEverything() {
            // On purpose: somebody has to be able to go and look at a broken warp, and an admin who
            // cannot reach the warp they are fixing fixes it by deleting it.
            assertThat(rule.mayUse(WarpAccess.STAFF, ADMIN)).isTrue();
            assertThat(rule.mayUse(new WarpAccess.Needing("rainswarps.warp.build"), ADMIN)).isTrue();
        }

        @Test
        @DisplayName("an admin who has had plain warping taken away still reaches them")
        void adminOutranksTheUseNode() {
            assertThat(rule.mayUse(WarpAccess.EVERYONE, holding(PermissionNodes.MANAGE))).isTrue();
        }

        @Test
        @DisplayName("nobody at all is refused rather than crashing")
        void nullIsRefused() {
            assertThat(rule.mayUse(WarpAccess.EVERYONE, null)).isFalse();
            assertThat(rule.mayUse(null, PLAYER))
                    .as("a warp whose access could not be read is refused, never opened")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("seeing one in the menu")
    class Seeing {

        @Test
        @DisplayName("what you cannot use, you are not shown")
        void restrictedWarpsAreHidden() {
            // Hidden rather than greyed, and this is the one deliberate exception to the module's
            // own grammar. Greying a staff warp shows every player that there is a warp called
            // "staffroom", which is the half of the secret that matters.
            assertThat(rule.maySee(WarpAccess.STAFF, PLAYER)).isFalse();
            assertThat(rule.maySee(WarpAccess.EVERYONE, PLAYER)).isTrue();
        }

        @Test
        @DisplayName("an admin sees everything, so they can manage it")
        void adminsSeeEverything() {
            assertThat(rule.maySee(WarpAccess.STAFF, ADMIN)).isTrue();
        }

        @Test
        @DisplayName("seeing and using agree, so no button refuses after the click")
        void seeingAndUsingAgree() {
            // A menu that offers something and then refuses it is a menu people press four times.
            for (WarpAccess access : java.util.List.of(WarpAccess.EVERYONE, WarpAccess.STAFF,
                    new WarpAccess.Needing("rainswarps.warp.build"))) {
                for (Predicate<String> who : java.util.List.of(NOBODY, PLAYER, STAFF, ADMIN)) {
                    assertThat(rule.maySee(access, who))
                            .as("%s is shown to somebody who cannot use it", access)
                            .isEqualTo(rule.mayUse(access, who));
                }
            }
        }
    }

    @Nested
    @DisplayName("changing one")
    class Managing {

        @Test
        @DisplayName("only an admin may")
        void onlyAdmins() {
            assertThat(rule.mayManage(ADMIN)).isTrue();
            assertThat(rule.mayManage(STAFF))
                    .as("holding the staff node is being allowed into the staff warps, not being "
                            + "allowed to move them")
                    .isFalse();
            assertThat(rule.mayManage(PLAYER)).isFalse();
            assertThat(rule.mayManage(null)).isFalse();
        }
    }
}
