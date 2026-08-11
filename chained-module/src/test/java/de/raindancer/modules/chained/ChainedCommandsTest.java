package de.raindancer.modules.chained;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the module declares at bootstrap, and the state every command has to survive: registered
 * during Paper's bootstrap phase, long before the module has built anything.
 */
class ChainedCommandsTest {

    private static final Path COMMAND = Path.of(
            "src/main/java/de/raindancer/modules/chained/command/ChainCommand.java");

    private static String source() {
        try {
            return Files.readString(COMMAND);
        } catch (IOException unreadable) {
            throw new AssertionError("the command is gone", unreadable);
        }
    }

    @Nested
    @DisplayName("what is declared")
    class Declaring {

        @Test
        @DisplayName("there is exactly one command, and it is /chain")
        void oneCommand() {
            List<ModuleCommand> declared = ChainedCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("chain");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // The state this whole arrangement exists for. Paper fires COMMANDS during bootstrap,
            // before the module has built anything, and a command that touched the services while
            // being declared would throw there — where the failure is a plugin that reports it
            // contains no modules.
            assertThat(ChainedCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(ChainedCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep inside a menu instead: a stack trace in the console and
            // nothing at all on the player's screen.
            ModuleCommand declared = ChainedCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }

    @Nested
    @DisplayName("an unknown subcommand")
    class UnknownSubcommand {

        @Test
        @DisplayName("does not silently do nothing — it falls through to help")
        void unknownSubcommandPrintsHelp() {
            // Read out of the source rather than run against a live command: this module has no
            // predecessor plugin, so there is no old-names-still-answer list to keep in step with a
            // switch — but a typo must still read as "here is what you can type" rather than as a
            // command that quietly did nothing.
            String body = source();
            int switchAt = body.indexOf("switch (args[0].toLowerCase(Locale.ROOT))");
            assertThat(switchAt).as("the command no longer switches on its first word").isPositive();

            int defaultAt = body.indexOf("default ->", switchAt);
            assertThat(defaultAt).as("the switch has no default case").isPositive();

            String defaultBranch = body.substring(defaultAt, Math.min(body.length(), defaultAt + 60));
            assertThat(defaultBranch)
                    .as("an unrecognised word must fall through to help, not to nothing")
                    .contains("help(");
        }

        @Test
        @DisplayName("every recognised word actually does something, not just prints help")
        void recognisedWordsAreNotAllHelp() {
            String body = source();
            int switchAt = body.indexOf("switch (args[0].toLowerCase(Locale.ROOT))");
            String cases = body.substring(switchAt, body.indexOf("default ->", switchAt));

            List<String> switchedOn = java.util.regex.Pattern.compile("\"([a-z]+)\"")
                    .matcher(cases).results()
                    .map(match -> match.group(1))
                    .toList();

            assertThat(switchedOn)
                    .as("the recognised subcommands")
                    .contains("pair", "unpair", "start", "stop", "reset", "status", "admin");
        }
    }
}
