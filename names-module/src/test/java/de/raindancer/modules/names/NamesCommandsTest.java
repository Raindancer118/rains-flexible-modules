package de.raindancer.modules.names;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.names.command.INamesCommand;
import de.raindancer.modules.names.util.PermissionNodes;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The command, as it exists during bootstrap.
 *
 * <p>Paper asks for the commands in the bootstrap phase — before the plugin object exists, before the
 * module has enabled, and before {@code Bukkit.getServer()} answers anything useful. So the one thing
 * that has to be true of {@link NamesCommands#declared()} is that it needs <em>nothing</em>: no server,
 * no services, no settings. A handler that captured any of those would either throw here or, far worse,
 * capture a null and fail on somebody's first {@code /namestyle}.
 */
class NamesCommandsTest {

    @Test
    @DisplayName("the commands can be declared with no server and no module running")
    void declaringNeedsNothing() {
        assertThatCode(NamesCommands::declared).doesNotThrowAnyException();
        assertThat(NamesCommands.isRunning())
                .as("nothing has enabled the module, so nothing may claim it is up")
                .isFalse();
    }

    @Test
    @DisplayName("Paper may ask more than once, and gets the same answer")
    void declaringIsRepeatable() {
        assertThat(NamesCommands.declared()).hasSameSizeAs(NamesCommands.declared());
    }

    @Test
    @DisplayName("the names the standalone plugin registered still answer")
    void theOldNamesAreKept() {
        // People type what they typed last week, including the spelling their keyboard's country uses.
        ModuleCommand namestyle = NamesCommands.declared().getFirst();

        assertThat(namestyle.name()).isEqualTo("namestyle");
        assertThat(namestyle.names()).containsExactlyInAnyOrder("namestyle", "namecolour", "namecolor");
    }

    @Test
    @DisplayName("the command describes itself, for the help and the diagnostic")
    void itSaysWhatItIsFor() {
        for (ModuleCommand command : NamesCommands.declared()) {
            assertThat(command.description()).isNotBlank();
            assertThat(command.handler()).isInstanceOf(INamesCommand.class);
            assertThat(((INamesCommand) command.handler()).describe()).isNotBlank();
        }
    }

    @Test
    @DisplayName("running the command before the module started refuses instead of dereferencing null")
    void beforeTheModuleIsUp() {
        // The host wraps every command in ModuleCommands.guarded, so this should be unreachable — but
        // the state is reachable three ways (before it starts, after it failed, after it stopped) and
        // the message is the useful half when it is.
        INamesCommand handler = (INamesCommand) NamesCommands.declared().getFirst().handler();

        assertThatCode(() -> handler.execute(null, new String[0]))
                .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("the manual is for everybody and the reload is not")
    void thePermissionDefaults() {
        // FALSE would mean nobody, the server owner included — the mistake that took ten commands from
        // the owner of the moderation module. And an unregistered node resolves to operators only,
        // which would refuse the manual to every ordinary player.
        List<Permission> declared = PermissionNodes.declared();

        assertThat(declared).extracting(Permission::getName)
                .containsExactlyInAnyOrder(PermissionNodes.USE, PermissionNodes.RELOAD);
        assertThat(defaultOf(declared, PermissionNodes.USE)).isEqualTo(PermissionDefault.TRUE);
        assertThat(defaultOf(declared, PermissionNodes.RELOAD)).isEqualTo(PermissionDefault.OP);
        assertThat(declared).extracting(Permission::getDefault).doesNotContain(PermissionDefault.FALSE);
    }

    @Test
    @DisplayName("the permission nodes are the ones already granted in somebody's LuckPerms")
    void thePermissionNodesAreUnchanged() {
        assertThat(PermissionNodes.USE).isEqualTo("colourednames.use");
        assertThat(PermissionNodes.RELOAD).isEqualTo("colourednames.reload");
    }

    @Test
    @DisplayName("every node says what it is for, or it is a string nobody can look up")
    void everyNodeIsDescribed() {
        assertThat(PermissionNodes.declared()).allSatisfy(permission ->
                assertThat(permission.getDescription()).isNotBlank());
    }

    private static PermissionDefault defaultOf(List<Permission> declared, String node) {
        return declared.stream()
                .filter(permission -> permission.getName().equals(node))
                .findFirst()
                .orElseThrow(() -> new AssertionError(node + " is not declared any more"))
                .getDefault();
    }
}
