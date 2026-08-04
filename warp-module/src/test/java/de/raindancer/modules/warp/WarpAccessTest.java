package de.raindancer.modules.warp;

import de.raindancer.modules.warp.model.WarpAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a warp is for.
 *
 * <h2>Why this is a value and not a boolean on the warp</h2>
 * Because there are three answers, not two: everybody, the staff, and whoever holds one particular
 * permission. A boolean forces the third to be expressed as the second, and then a server that wants
 * builders to reach the build world has to make every builder staff.
 *
 * <h2>Why it is stored as a permission node and nothing else</h2>
 * RainsCore already keeps a permission on a place, and every one of these three is a question about
 * a permission. Adding a second tag saying "this one is staff" would mean two things to keep in step
 * and one of them winning when they disagree — and the one that wins would decide whether the staff
 * warp is open to the server.
 */
class WarpAccessTest {

    /** Somebody with these nodes and no others. */
    private static java.util.function.Predicate<String> holding(String... nodes) {
        Set<String> held = Set.of(nodes);
        return held::contains;
    }

    @Nested
    @DisplayName("reading it off a warp")
    class Reading {

        @Test
        @DisplayName("no permission at all means everybody")
        void nothingIsEverybody() {
            assertThat(WarpAccess.from(null)).isEqualTo(WarpAccess.EVERYONE);
            assertThat(WarpAccess.from("")).isEqualTo(WarpAccess.EVERYONE);
            assertThat(WarpAccess.from("   ")).isEqualTo(WarpAccess.EVERYONE);
        }

        @Test
        @DisplayName("the staff node means the staff")
        void theStaffNodeIsStaff() {
            assertThat(WarpAccess.from(WarpAccess.STAFF_PERMISSION)).isEqualTo(WarpAccess.STAFF);
        }

        @Test
        @DisplayName("the staff node is recognised however it was capitalised")
        void theStaffNodeIsCaseInsensitive() {
            // Permissions are lower case by convention and not by rule, and an admin who typed it
            // into the config with a capital would otherwise get a warp that says "needs
            // RainsWarps.Warp.Staff" instead of "staff only" — which still works and reads as a bug.
            assertThat(WarpAccess.from("RainsWarps.Warp.Staff")).isEqualTo(WarpAccess.STAFF);
        }

        @Test
        @DisplayName("anything else is that permission and nothing more")
        void anythingElseIsItsOwnNode() {
            assertThat(WarpAccess.from("rainswarps.warp.mine"))
                    .isEqualTo(new WarpAccess.Needing("rainswarps.warp.mine"));
        }

        @Test
        @DisplayName("it goes back onto the warp as the node it came from")
        void itRoundTrips() {
            for (WarpAccess access : List.of(WarpAccess.EVERYONE, WarpAccess.STAFF,
                    new WarpAccess.Needing("rainswarps.warp.mine"))) {
                assertThat(WarpAccess.from(access.permission().orElse(null)))
                        .as("%s does not survive being written and read back", access)
                        .isEqualTo(access);
            }
        }

        @Test
        @DisplayName("everybody is stored as nothing, not as a node everybody has")
        void everybodyIsStoredAsNothing() {
            // A node that defaults to true would work until somebody negated it in a permissions
            // plugin, at which point the public warps would quietly become staff warps.
            assertThat(WarpAccess.EVERYONE.permission()).isEmpty();
        }
    }

    @Nested
    @DisplayName("who may use it")
    class Allowing {

        @Test
        @DisplayName("everybody means everybody, including somebody with no permissions at all")
        void everybodyMeansEverybody() {
            assertThat(WarpAccess.EVERYONE.allows(holding())).isTrue();
        }

        @Test
        @DisplayName("a staff warp needs the staff node")
        void staffNeedsTheStaffNode() {
            assertThat(WarpAccess.STAFF.allows(holding())).isFalse();
            assertThat(WarpAccess.STAFF.allows(holding(WarpAccess.STAFF_PERMISSION))).isTrue();
        }

        @Test
        @DisplayName("a warp with its own node needs that node")
        void itsOwnNodeIsWhatItNeeds() {
            WarpAccess builders = new WarpAccess.Needing("rainswarps.warp.build");

            assertThat(builders.allows(holding("rainswarps.warp.build"))).isTrue();
            assertThat(builders.allows(holding("rainswarps.warp.other"))).isFalse();
        }

        @Test
        @DisplayName("the staff node does not open a warp that asked for a different one")
        void staffIsNotASkeletonKey() {
            // Deliberately. "Staff" and "the people who may reach the build world" are different
            // groups on every server that has both, and a staff node that opened everything would
            // make the third kind of warp pointless.
            //
            // What does open everything is the admin node, which is asked separately — see
            // WarpAccessRule, where an admin is let past on purpose because somebody has to be able
            // to fix a warp they cannot use.
            assertThat(new WarpAccess.Needing("rainswarps.warp.build")
                    .allows(holding(WarpAccess.STAFF_PERMISSION))).isFalse();
        }

        @Test
        @DisplayName("nobody at all is refused rather than crashing")
        void nobodyIsRefused() {
            assertThat(WarpAccess.STAFF.allows(null)).isFalse();
            assertThat(WarpAccess.EVERYONE.allows(null))
                    .as("a warp everybody may use needs nothing asked of anybody")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a warp's own node")
    class OwnNode {

        @Test
        @DisplayName("it is built from the name, in lower case")
        void itIsBuiltFromTheName() {
            assertThat(WarpAccess.ownPermissionFor("TheMine"))
                    .isEqualTo("rainswarps.warp.themine");
        }

        @Test
        @DisplayName("anything that is not a permission character is dropped")
        void itIsCleanedUp() {
            // A permission node with a space in it can be granted and never matched, which reads to
            // an admin as a permissions plugin that has stopped working.
            assertThat(WarpAccess.ownPermissionFor("The Old Quarry!"))
                    .isEqualTo("rainswarps.warp.theoldquarry");
        }

        @Test
        @DisplayName("a name with nothing usable in it gets the staff node instead")
        void anEmptyNameFallsBackToStaff() {
            // Never a bare "rainswarps.warp." — that is a node nobody can be granted, so the warp
            // would be reachable by nobody at all with no way to tell why.
            assertThat(WarpAccess.ownPermissionFor("！！！")).isEqualTo(WarpAccess.STAFF_PERMISSION);
            assertThat(WarpAccess.ownPermissionFor(null)).isEqualTo(WarpAccess.STAFF_PERMISSION);
        }
    }

    @Nested
    @DisplayName("saying which it is")
    class Describing {

        @Test
        @DisplayName("each one says something different and none of them is blank")
        void eachOneReads() {
            List<WarpAccess> all = List.of(WarpAccess.EVERYONE, WarpAccess.STAFF,
                    new WarpAccess.Needing("rainswarps.warp.mine"));

            assertThat(all).allSatisfy(access ->
                    assertThat(access.describe()).isNotBlank());
            assertThat(all.stream().map(WarpAccess::describe).distinct())
                    .as("two of them reading the same is a menu where you cannot tell a staff warp "
                            + "from a public one")
                    .hasSize(all.size());
        }

        @Test
        @DisplayName("a warp with its own node says which node")
        void itNamesTheNode() {
            assertThat(new WarpAccess.Needing("rainswarps.warp.mine").describe())
                    .contains("rainswarps.warp.mine");
        }

        @Test
        @DisplayName("only the ones nobody sees by default count as hidden")
        void hiddenIsTheOnesThatAreHidden() {
            assertThat(WarpAccess.EVERYONE.isRestricted()).isFalse();
            assertThat(WarpAccess.STAFF.isRestricted()).isTrue();
            assertThat(new WarpAccess.Needing("rainswarps.warp.mine").isRestricted()).isTrue();
        }
    }
}
