package de.raindancer.modules.warp;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.warp.command.WarpCommand;
import de.raindancer.modules.warp.rules.WarpNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the module declares at bootstrap, and the hole it must not have.
 *
 * <h2>The hole</h2>
 * {@code /warp} reads its first word as a subcommand before it reads it as a warp's name. So a warp
 * called {@code list} appears in the menu, can be clicked, and can never be reached by the command —
 * {@code /warp list} prints the list instead. RainsCore's own {@code /warp} still has that; this
 * module does not, because {@link WarpNameRule} refuses those names and the command takes its
 * subcommands from the same list rather than a second copy of it.
 *
 * <p>Which is the thing worth testing: the two lists being one list. Two copies would work on the day
 * they were written and drift the first time a subcommand was added.
 */
class WarpCommandsTest {

    private static final Path COMMAND = Path.of(
            "src/main/java/de/raindancer/modules/warp/command/WarpCommand.java");

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
        @DisplayName("there is exactly one command, and it is /warp")
        void oneCommand() {
            List<ModuleCommand> declared = WarpCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("warp");
        }

        @Test
        @DisplayName("the names people already type still answer")
        void theOldNamesAnswer() {
            assertThat(WarpCommands.declared().getFirst().names())
                    .as("people type the plural, and somebody coming from another plugin types "
                            + "what that one used")
                    .contains("warp", "warps");
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // The state this whole arrangement exists for. Paper fires COMMANDS during bootstrap,
            // before the module has built anything, and a command that touched the services while
            // being declared would throw there — where the failure is a plugin that reports it
            // contains no modules.
            assertThat(WarpCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(WarpCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep inside a menu instead: a stack trace in the console and
            // nothing at all on the player's screen.
            ModuleCommand declared = WarpCommands.declared().getFirst();

            assertThatCode(() -> declared.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }

    @Nested
    @DisplayName("the subcommands and the refused names are one list")
    class OneList {

        @Test
        @DisplayName("the command's own list is the name rule's list")
        void theListIsShared() {
            assertThat(WarpCommand.subcommands())
                    .as("two copies would agree on the day they were written and drift the first "
                            + "time a subcommand was added — leaving a warp name silently "
                            + "unreachable")
                    .isSameAs(WarpNameRule.RESERVED);
        }

        @Test
        @DisplayName("every word the command switches on is a refused name")
        void nothingIsSwitchedOnWithoutBeingRefused() {
            // Read out of the source rather than listed here, so a subcommand added to the switch
            // and not to the list fails this instead of quietly making a warp unreachable.
            String body = source();
            int switchAt = body.indexOf("switch (args[0].toLowerCase(Locale.ROOT))");
            assertThat(switchAt).as("the command no longer switches on its first word").isPositive();
            String cases = body.substring(switchAt, body.indexOf("default ->", switchAt));

            List<String> switchedOn = java.util.regex.Pattern.compile("\"([a-z]+)\"")
                    .matcher(cases).results()
                    .map(match -> match.group(1))
                    .toList();

            assertThat(switchedOn).as("no cases were found, so this rule is checking nothing")
                    .isNotEmpty();
            for (String word : switchedOn) {
                assertThat(WarpNameRule.RESERVED)
                        .as("/warp %s is read as an instruction, so a warp of that name could "
                                + "never be reached — the rule has to refuse it", word)
                        .contains(word.toLowerCase(Locale.ROOT));
            }
        }
    }
}
