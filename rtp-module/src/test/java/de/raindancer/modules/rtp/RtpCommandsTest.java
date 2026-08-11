package de.raindancer.modules.rtp;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the module declares at bootstrap, and the state it must survive: registered before it runs.
 */
class RtpCommandsTest {

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there is exactly one command, and it is /rtp")
        void oneCommand() {
            List<ModuleCommand> declared = RtpCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("rtp");
        }

        @Test
        @DisplayName("the names people already type elsewhere still answer")
        void theCommonNamesAnswer() {
            assertThat(RtpCommands.declared().getFirst().names())
                    .as("'wild' and 'randomtp' are what other plugins call this — somebody coming "
                            + "from one of those types what they already know")
                    .contains("rtp", "wild", "randomtp");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // Paper fires COMMANDS during bootstrap, before the module has built anything, and a
            // command that touched the services while being declared would throw there — where the
            // failure is a plugin that reports it contains no modules.
            assertThat(RtpCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(RtpCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep in a command instead: a stack trace in the console and
            // nothing at all on the player's screen.
            ModuleCommand declared = RtpCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }

        @Test
        @DisplayName("the command needs the use permission")
        void needsThePermission() {
            assertThat(RtpCommands.declared().getFirst().permission())
                    .isEqualTo(de.raindancer.modules.rtp.util.PermissionNodes.USE);
        }
    }
}
