package de.raindancer.modules.worldgate;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.worldgate.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the module declares at bootstrap, and the state it must survive: registered before it runs.
 */
class WorldGateCommandsTest {

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there is exactly one command, and it is /worldgate")
        void oneCommand() {
            List<ModuleCommand> declared = WorldGateCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("worldgate");
        }

        @Test
        @DisplayName("the two names from the spec both answer")
        void theSpecifiedAliasesAnswer() {
            assertThat(WorldGateCommands.declared().getFirst().names())
                    .contains("worldgate", "wgate", "dimensions");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // Paper fires COMMANDS during bootstrap, before the module has built anything, and a
            // command that touched the services while being declared would throw there — where the
            // failure is a plugin that reports it contains no modules.
            assertThat(WorldGateCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(WorldGateCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep in a command instead: a stack trace in the console and
            // nothing at all on the player's screen.
            ModuleCommand declared = WorldGateCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }

        @Test
        @DisplayName("the command needs the low-bar status permission, not the admin one")
        void needsTheLowBarPermission() {
            // status must work for an ordinary player, so the command itself is gated on the
            // permission everybody holds by default; lock/open/evacuate each check the admin
            // permission for themselves inside the handler.
            assertThat(WorldGateCommands.declared().getFirst().permission())
                    .isEqualTo(PermissionNodes.STATUS);
        }

        @Test
        @DisplayName("the usage lines mention every subcommand the spec asks for")
        void usageListsEverySubcommand() {
            List<String> options = WorldGateCommands.declared().getFirst().options();

            assertThat(options).hasSizeGreaterThanOrEqualTo(4);
            assertThat(String.join(" ", options))
                    .contains("status").contains("lock").contains("open").contains("evacuate");
        }
    }
}
