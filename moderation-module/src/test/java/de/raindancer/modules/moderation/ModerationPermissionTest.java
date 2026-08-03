package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.ModerationPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission nodes, which are the one part of a plugin an owner has to type by hand.
 *
 * <h2>Why they are an enum and not strings at the call sites</h2>
 * Because a stringly typed permission is a typo that compiles, has no find-usages, and fails open or
 * closed depending on which side of the check it is on. The version this replaces had
 * {@code "rainsmoderation.ban"} written out in four files, one of them as
 * {@code "rainsmoderation.bans"}.
 */
class ModerationPermissionTest {

    @Test
    @DisplayName("every node is under one prefix, so a wildcard grant actually grants everything")
    void oneFamily() {
        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(permission.node())
                    .as("%s", permission)
                    .startsWith(ModerationPermission.PREFIX);
        }
    }

    @Test
    @DisplayName("no two permissions share a node")
    void nodesAreUnique() {
        List<String> nodes = new ArrayList<>();
        for (ModerationPermission permission : ModerationPermission.values()) {
            nodes.add(permission.node());
        }

        assertThat(nodes)
                .as("two permissions with one node is a helper who can mute quietly gaining the ability "
                        + "to ban")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every node is lower case and has no spaces, because Bukkit matches them literally")
    void nodesAreWellFormed() {
        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(permission.node())
                    .isEqualTo(permission.node().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain(" ");
        }
    }

    @Test
    @DisplayName("every permission says what it lets somebody do")
    void everyPermissionIsDescribed() {
        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(permission.describe())
                    .as("%s has no description, so the page that lists them says nothing", permission)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("the powers a server would want to split are separate permissions")
    void thePowersAreSplit() {
        // A helper who can mute must not thereby be able to ban, and somebody who can look at an
        // inventory must not thereby be able to edit it. Both of those were one node before.
        List<String> names = new ArrayList<>();
        for (ModerationPermission permission : ModerationPermission.values()) {
            names.add(permission.name());
        }

        assertThat(names).contains("BAN", "MUTE", "KICK", "WARN", "FREEZE", "HISTORY", "NOTES",
                "REPORTS", "VANISH", "INVSEE", "INVSEE_EDIT", "STAFF_CHAT");
    }
}
