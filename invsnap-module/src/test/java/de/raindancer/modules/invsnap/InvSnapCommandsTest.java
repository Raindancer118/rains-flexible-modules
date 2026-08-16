package de.raindancer.modules.invsnap;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** What the module declares at bootstrap, and the state it must survive: registered before it runs. */
class InvSnapCommandsTest {

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there is exactly one command, and it is /invsnap")
        void oneCommand() {
            List<ModuleCommand> declared = InvSnapCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("invsnap");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            assertThat(InvSnapCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(InvSnapCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            ModuleCommand declared = InvSnapCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }

        @Test
        @DisplayName("the command needs the browse permission")
        void needsThePermission() {
            assertThat(InvSnapCommands.declared().getFirst().permission())
                    .isEqualTo(PermissionNodes.BROWSE);
        }

        @Test
        @DisplayName("it is worth a line in the audit journal")
        void isAudited() {
            assertThat(InvSnapCommands.declared().getFirst().audited()).isTrue();
        }

        @Test
        @DisplayName("it documents its one argument")
        void documentsItsOptions() {
            assertThat(InvSnapCommands.declared().getFirst().options())
                    .isNotEmpty()
                    .anyMatch(option -> option.contains("player"));
        }
    }
}
