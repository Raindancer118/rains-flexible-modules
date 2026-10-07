package de.raindancer.modules.warp;

import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warps players own: made themselves, with a token, or handed to them by staff — and theirs to decide
 * about, but nobody else's.
 */
class WarpOwnershipTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final WarpAccessRule rule = new WarpAccessRule();

    private static Predicate<String> holding(String... nodes) {
        Set<String> held = Set.of(nodes);
        return held::contains;
    }

    private static final Predicate<String> PLAYER = holding(PermissionNodes.USE);
    private static final Predicate<String> MAKER = holding(PermissionNodes.USE, PermissionNodes.CREATE);
    private static final Predicate<String> ADMIN = holding(PermissionNodes.USE, PermissionNodes.MANAGE);

    @Nested
    @DisplayName("a private warp")
    class Private {

        @Test
        @DisplayName("is for its owner and the people they added, and nobody else")
        void ownerAndMembers() {
            Set<UUID> members = Set.of(FRIEND);

            assertThat(rule.mayUse(WarpAccess.PRIVATE, PLAYER, OWNER, OWNER, members)).isTrue();
            assertThat(rule.mayUse(WarpAccess.PRIVATE, PLAYER, FRIEND, OWNER, members)).isTrue();
            assertThat(rule.mayUse(WarpAccess.PRIVATE, PLAYER, STRANGER, OWNER, members)).isFalse();
        }

        @Test
        @DisplayName("is still the staff's to reach — somebody has to be able to look at a broken one")
        void adminReachesIt() {
            assertThat(rule.mayUse(WarpAccess.PRIVATE, ADMIN, STRANGER, OWNER, Set.of())).isTrue();
        }

        @Test
        @DisplayName("is not opened by holding some node: it is about who, not what they were granted")
        void noNodeOpensIt() {
            Predicate<String> everything = node -> true;
            assertThat(rule.mayUse(WarpAccess.PRIVATE, node -> !node.equals(PermissionNodes.MANAGE)
                    && everything.test(node), STRANGER, OWNER, Set.of())).isFalse();
        }

        @Test
        @DisplayName("survives being written down and read back")
        void roundTrips() {
            assertThat(WarpAccess.from(WarpAccess.PRIVATE.permission().orElseThrow()))
                    .isEqualTo(WarpAccess.PRIVATE);
        }

        @Test
        @DisplayName("a player who may not warp at all does not reach their own warp either")
        void useCanStillBeTakenAway() {
            assertThat(rule.mayUse(WarpAccess.EVERYONE, holding(), OWNER, OWNER, Set.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("changing one")
    class Changing {

        @Test
        @DisplayName("its owner may, a stranger may not, staff always may")
        void owners() {
            assertThat(rule.mayChange(PLAYER, OWNER, OWNER)).isTrue();
            assertThat(rule.mayChange(PLAYER, STRANGER, OWNER)).isFalse();
            assertThat(rule.mayChange(ADMIN, STRANGER, OWNER)).isTrue();
            assertThat(rule.mayChange(PLAYER, STRANGER, null)).as("a warp nobody owns").isFalse();
        }

        @Test
        @DisplayName("an owner may open it to everybody or keep it private — staff and permission nodes stay the staff's")
        void ownersChooseOnlyBetweenTwo() {
            assertThat(rule.mayChooseAccess(WarpAccess.EVERYONE, PLAYER)).isTrue();
            assertThat(rule.mayChooseAccess(WarpAccess.PRIVATE, PLAYER)).isTrue();
            assertThat(rule.mayChooseAccess(WarpAccess.STAFF, PLAYER)).isFalse();
            assertThat(rule.mayChooseAccess(new WarpAccess.Needing("some.node"), PLAYER)).isFalse();
            assertThat(rule.mayChooseAccess(WarpAccess.STAFF, ADMIN)).isTrue();
        }

        @Test
        @DisplayName("giving a warp to somebody is the staff's alone")
        void givingAway() {
            assertThat(rule.mayGive(PLAYER)).isFalse();
            assertThat(rule.mayGive(ADMIN)).isTrue();
        }
    }

    @Nested
    @DisplayName("making one")
    class Making {

        @Test
        @DisplayName("needs the node, a token, or being staff")
        void whoMayMake() {
            assertThat(rule.mayCreate(PLAYER, false)).isFalse();
            assertThat(rule.mayCreate(PLAYER, true)).isTrue();
            assertThat(rule.mayCreate(MAKER, false)).isTrue();
            assertThat(rule.mayCreate(ADMIN, false)).isTrue();
        }

        @Test
        @DisplayName("with the node, only up to the server's limit per player; a token is a warp on top; staff have none")
        void limits() {
            assertThat(rule.hasRoomForOwn(2, 3, MAKER, false)).isTrue();
            assertThat(rule.hasRoomForOwn(3, 3, MAKER, false)).isFalse();
            assertThat(rule.hasRoomForOwn(3, 3, MAKER, true)).isTrue();
            assertThat(rule.hasRoomForOwn(99, 3, ADMIN, false)).isTrue();
        }

        @Test
        @DisplayName("a name already taken by somebody else's warp is not replaced by somebody who does not own it")
        void namesAreNotStolen() {
            assertThat(rule.mayReplace(PLAYER, OWNER, OWNER)).isTrue();
            assertThat(rule.mayReplace(PLAYER, STRANGER, OWNER)).isFalse();
            assertThat(rule.mayReplace(ADMIN, STRANGER, OWNER)).isTrue();
        }
    }
}
