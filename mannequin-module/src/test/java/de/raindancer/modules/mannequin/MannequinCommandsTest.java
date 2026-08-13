package de.raindancer.modules.mannequin;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.mannequin.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** What the module declares at bootstrap, and the state it must survive: registered before it runs. */
class MannequinCommandsTest {

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there is exactly one command, and it is /mannequin")
        void oneCommand() {
            List<ModuleCommand> declared = MannequinCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("mannequin");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            assertThat(MannequinCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(MannequinCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            ModuleCommand declared = MannequinCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }

        @Test
        @DisplayName("the command needs the use permission")
        void needsThePermission() {
            assertThat(MannequinCommands.declared().getFirst().permission())
                    .isEqualTo(PermissionNodes.USE);
        }

        @Test
        @DisplayName("it documents its subcommands")
        void documentsItsOptions() {
            assertThat(MannequinCommands.declared().getFirst().options())
                    .isNotEmpty()
                    .anyMatch(option -> option.contains("create"));
        }
    }
}
