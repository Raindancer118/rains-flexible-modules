package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.command.PromoteCommand;
import de.raindancer.modules.moderation.command.ReportCommand;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.util.PermissionNodes;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the server owner can use the moderation commands out of the box.
 *
 * <h2>The defect this exists because of, which I caused</h2>
 * Registering the staff nodes was the right idea — an <em>unregistered</em> permission resolves against
 * {@code Permission.DEFAULT_PERMISSION}, which is {@code OP}, so {@code /report} refused every ordinary
 * player. But they were registered as {@link PermissionDefault#FALSE}, and {@code FALSE} does not mean
 * "not by default". It means <b>nobody</b>:
 *
 * <pre>
 *   TRUE    → everybody
 *   OP      → operators
 *   NOT_OP  → everybody except operators
 *   FALSE   → nobody, operators included
 * </pre>
 *
 * <p>So the fix took {@code /ban}, {@code /mute}, {@code /kick}, {@code /warn}, {@code /history},
 * {@code /vanish}, {@code /invsee}, {@code /reports}, {@code /staffchat} and {@code /mod} away from the
 * server owner — who, before it, could use all of them. It built, it started, it logged nothing.
 *
 * <p>Hence this test, which asserts the property rather than the values: <b>nothing a command gates on
 * may default to {@code FALSE}</b>, because that is a command nobody at all can run.
 */
class PermissionDefaultsTest {

    private final List<Permission> declared = PermissionNodes.declared();

    @Test
    @DisplayName("the list is not empty, so this cannot pass by declaring nothing")
    void theScanIsNotVacuous() {
        assertThat(declared).isNotEmpty();
        assertThat(declared).hasSizeGreaterThanOrEqualTo(ModerationPermission.values().length);
    }

    @Test
    @DisplayName("nothing a command gates on is set to FALSE, which means nobody")
    void nothingIsUnusableByEverybody() {
        List<String> unusable = new ArrayList<>();
        for (Permission permission : declared) {
            if (permission.getDefault() == PermissionDefault.FALSE) {
                unusable.add(permission.getName());
            }
        }

        assertThat(unusable)
                .as("FALSE means nobody at all, operators included — a command behind one of these is a "
                        + "command the server owner cannot run and nothing explains why")
                .isEmpty();
    }

    @Test
    @DisplayName("the server owner holds every staff permission without being granted anything")
    void theOwnerHoldsEverything() {
        // What "the owner is the top rank" has to mean in practice: a fresh server, one op, and every
        // command works. Anything else and the first thing an owner has to do is promote themselves.
        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(defaultOf(permission.node()))
                    .as("%s should be held by an operator", permission)
                    .isIn(PermissionDefault.OP, PermissionDefault.TRUE);
        }
    }

    @Test
    @DisplayName("no staff permission is held by an ordinary player")
    void playersHoldNothing() {
        // The other half. TRUE here would hand every player the ban command.
        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(defaultOf(permission.node()))
                    .as("%s must not be on for everybody", permission)
                    .isNotIn(PermissionDefault.TRUE, PermissionDefault.NOT_OP);
        }
    }

    @Test
    @DisplayName("every player may report somebody")
    void everybodyMayReport() {
        // The node that started all of this: unregistered, it read as false for every player, so the
        // one command in the module a player runs refused every one of them.
        assertThat(defaultOf(ReportCommand.USE))
                .as("a report command that only operators can run is a report command nobody uses")
                .isEqualTo(PermissionDefault.TRUE);
    }

    @Test
    @DisplayName("handing out ranks is the owner's, and no preset grants it")
    void promotingIsTheOwners() {
        assertThat(defaultOf(PromoteCommand.USE)).isEqualTo(PermissionDefault.OP);

        // The rule the whole ladder rests on: a power that hands out powers must not be one of the
        // powers it hands out.
        assertThat(de.raindancer.modules.moderation.model.StaffRank.everyGrantableNode())
                .doesNotContain(PromoteCommand.USE);
    }

    @Test
    @DisplayName("an operator is immune to moderators, and nobody else is by default")
    void immunityIsTheOwners() {
        // So a mod cannot ban the owner on a fresh server, before anybody has been given a rank.
        assertThat(defaultOf(StaffRule.IMMUNE)).isEqualTo(PermissionDefault.OP);
    }

    @Test
    @DisplayName("every node the commands check is declared")
    void nothingTheCodeUsesIsMissing() {
        List<String> names = declared.stream().map(Permission::getName).toList();

        for (ModerationPermission permission : ModerationPermission.values()) {
            assertThat(names)
                    .as("%s is checked and never declared, so it resolves to the OP default silently",
                            permission)
                    .contains(permission.node());
        }
        assertThat(names).contains(StaffRule.IMMUNE, PromoteCommand.USE, ReportCommand.USE);
    }

    @Test
    @DisplayName("no node is declared twice")
    void noDuplicates() {
        List<String> names = new ArrayList<>(declared.stream().map(Permission::getName).toList());

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every node says what it is for")
    void everyNodeIsDescribed() {
        assertThat(declared).allSatisfy(permission ->
                assertThat(permission.getDescription()).isNotBlank());
    }

    private PermissionDefault defaultOf(String node) {
        return declared.stream()
                .filter(permission -> permission.getName().equals(node))
                .findFirst()
                .map(Permission::getDefault)
                .orElseThrow(() -> new AssertionError(node + " is not declared"));
    }
}
