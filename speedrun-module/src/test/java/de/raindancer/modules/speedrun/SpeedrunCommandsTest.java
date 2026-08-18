package de.raindancer.modules.speedrun;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the module declares at bootstrap, and the state it must survive: registered before it runs.
 * Mirrors {@code RtpCommandsTest}'s idiom.
 */
class SpeedrunCommandsTest {

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there are exactly five commands, with the names asked for")
        void fiveCommands() {
            List<ModuleCommand> declared = SpeedrunCommands.declared();

            assertThat(declared).hasSize(5);
            assertThat(declared.stream().map(ModuleCommand::name)).containsExactlyInAnyOrder(
                    "speedrun", "lemmemove", "starthere", "speedrunreset", "speedrunspectate");
        }

        @Test
        @DisplayName("speedrun needs the join permission")
        void joinNeedsItsOwnPermission() {
            assertThat(byName("speedrun").permission()).isEqualTo(PermissionNodes.JOIN);
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // Paper fires COMMANDS during bootstrap, before the module has built anything, and a
            // command that touched the lobby while being declared would throw there — where the
            // failure is a plugin that reports it contains no modules.
            assertThat(SpeedrunCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(SpeedrunCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running one before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep in a command instead: a stack trace in the console and
            // nothing at all on the player's screen.
            for (ModuleCommand declared : SpeedrunCommands.declared()) {
                assertThatCode(() -> declared.handler().execute(null, new String[0]))
                        .as("/%s", declared.name())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not running");
            }
        }

        @Test
        @DisplayName("lemmemove needs only the self permission — the others check happens inside it")
        void lemmemoveNeedsTheSelfPermission() {
            assertThat(byName("lemmemove").permission()).isEqualTo(PermissionNodes.LEMMEMOVE_SELF);
        }

        @Test
        @DisplayName("starthere and speedrunreset both need the admin permission")
        void adminCommandsNeedTheAdminPermission() {
            assertThat(byName("starthere").permission()).isEqualTo(PermissionNodes.ADMIN);
            assertThat(byName("speedrunreset").permission()).isEqualTo(PermissionNodes.ADMIN);
        }

        @Test
        @DisplayName("speedrunspectate needs the spectate permission")
        void spectateNeedsItsOwnPermission() {
            assertThat(byName("speedrunspectate").permission()).isEqualTo(PermissionNodes.SPECTATE);
        }

        @Test
        @DisplayName("speedrunreset is audited; the other four are not")
        void onlyResetIsAudited() {
            assertThat(byName("speedrunreset").audited()).isTrue();
            assertThat(byName("speedrun").audited()).isFalse();
            assertThat(byName("lemmemove").audited()).isFalse();
            assertThat(byName("starthere").audited()).isFalse();
            assertThat(byName("speedrunspectate").audited()).isFalse();
        }

        private ModuleCommand byName(String name) {
            return SpeedrunCommands.declared().stream()
                    .filter(command -> command.name().equals(name))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
