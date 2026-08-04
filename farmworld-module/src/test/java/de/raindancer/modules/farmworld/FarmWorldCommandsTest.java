package de.raindancer.modules.farmworld;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.farmworld.command.FarmWorldCommand;
import de.raindancer.modules.farmworld.rules.FarmWorldNameRule;
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
 * {@code /farm} reads its first word as a subcommand before it reads it as a farm world's name. So a farm world
 * called {@code list} would appear in the menu, be clickable, and never be reachable by typing —
 * {@code /farm list} prints the list instead. {@link FarmWorldNameRule} refuses those names and the command takes
 * its subcommands from the same list rather than a second copy of it.
 *
 * <p>Which is the thing worth testing: the two lists being one list. Two copies would work on the day they were
 * written and drift the first time a subcommand was added.
 */
class FarmWorldCommandsTest {

    private static final Path COMMAND = Path.of(
            "src/main/java/de/raindancer/modules/farmworld/command/FarmWorldCommand.java");

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
        @DisplayName("there is exactly one command, and it is /farm")
        void oneCommand() {
            List<ModuleCommand> declared = FarmWorldCommands.declared();

            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst().name()).isEqualTo("farm");
        }

        @Test
        @DisplayName("the names people already type still answer")
        void theOldNamesAnswer() {
            assertThat(FarmWorldCommands.declared().getFirst().names())
                    .as("farmworld is what Core's own plain command is called, and farmwelt is what the "
                            + "people on this server actually say")
                    .contains("farm", "farmworld", "farmwelt");
        }

        @Test
        @DisplayName("it is declared with the node an ordinary player holds")
        void anOrdinaryPlayerCanSeeIt() {
            // The lower of the two. Declared with the managing node instead, the command would be absent from
            // every player's help — and a farm world nobody can find is one nobody has.
            assertThat(FarmWorldCommands.declared().getFirst().permission())
                    .isEqualTo("rainsfarmworlds.farm.use");
        }

        @Test
        @DisplayName("it says what it takes, so the directory book cannot go stale")
        void theOptionsAreDeclared() {
            assertThat(FarmWorldCommands.declared().getFirst().options())
                    .as("the book is generated from exactly this list, so a command whose options live in two "
                            + "places has options that disagree by March")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("it can be declared before anything is built")
        void nothingIsCapturedAtBootstrap() {
            // The state this whole arrangement exists for. Paper fires COMMANDS during bootstrap, before the
            // module has built anything, and a command that touched the services while being declared would
            // throw there — where the failure is a plugin that reports it contains no modules.
            assertThat(FarmWorldCommands.isRunning())
                    .as("nothing has started, so nothing should think it has")
                    .isFalse();
            assertThatCode(FarmWorldCommands::declared).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("running it before the module starts throws rather than returning nothing")
        void itRefusesRatherThanReturningNull() {
            // The host's guard turns this into one red line naming the module. A null would be a
            // NullPointerException deep inside a menu instead: a stack trace in the console and nothing at all
            // on the player's screen.
            ModuleCommand declared = FarmWorldCommands.declared().getFirst();

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
            assertThat(FarmWorldCommand.subcommands())
                    .as("two copies would agree on the day they were written and drift the first time a "
                            + "subcommand was added — leaving a farm world name silently unreachable")
                    .isSameAs(FarmWorldNameRule.RESERVED);
        }

        @Test
        @DisplayName("every word the command switches on is a refused name")
        void nothingIsSwitchedOnWithoutBeingRefused() {
            // Read out of the source rather than listed here, so a subcommand added to the switch and not to the
            // list fails this instead of quietly making a farm world unreachable.
            String body = source();
            int switchAt = body.indexOf("switch (args[0].toLowerCase(Locale.ROOT))");
            assertThat(switchAt).as("the command no longer switches on its first word").isPositive();
            String cases = body.substring(switchAt, body.indexOf("default ->", switchAt));

            List<String> switchedOn = java.util.regex.Pattern.compile("\"([a-z]+)\"")
                    .matcher(cases).results()
                    .map(match -> match.group(1))
                    .toList();

            assertThat(switchedOn).as("no cases were found, so this rule is checking nothing").isNotEmpty();
            for (String word : switchedOn) {
                assertThat(FarmWorldNameRule.RESERVED)
                        .as("/farm %s is read as an instruction, so a farm world of that name could never be "
                                + "reached — the rule has to refuse it", word)
                        .contains(word.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Nested
    @DisplayName("the one command that deletes worlds")
    class Regenerating {

        @Test
        @DisplayName("it asks twice, and the second word is not completed")
        void itAsksTwice() {
            // The console has no inventory, so it cannot be shown the confirmation page the menu uses — and the
            // thing behind the button deletes three worlds. A second word typed deliberately is the console's
            // version of that page, and completing it would turn it back into one keystroke.
            String body = source();

            assertThat(body)
                    .as("regen has to want the word confirm after the name")
                    .contains("equalsIgnoreCase(\"confirm\")");
            assertThat(body.substring(body.indexOf("public @NotNull Collection<String> suggest")))
                    .as("the confirmation must not be offered by tab completion")
                    .doesNotContain("\"confirm\"");
        }

        @Test
        @DisplayName("it is behind the managing node, not the one every player holds")
        void onlyAnAdminReachesIt() {
            String body = source();
            int at = body.indexOf("private void regenerate(");
            assertThat(at).as("the regenerate branch is gone").isPositive();

            assertThat(body.substring(at, at + 400))
                    .as("every branch that changes anything asks for MANAGE itself — the command's own "
                            + "permission only decides whether it appears at all")
                    .contains("mayManage");
        }
    }
}
