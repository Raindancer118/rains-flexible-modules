package de.raindancer.modules.moderation;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.moderation.command.IModerationCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the module declares before anything exists.
 *
 * <h2>The state this is really about</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during bootstrap — before the plugin object, before
 * RainsCore, before this module. A handler registered later never runs at all: no warning, no
 * exception, the command simply does not exist. So {@code commands()} is called at the worst possible
 * moment, and everything below is a way of asking "does this work with nothing running?".
 *
 * <p>Which is exactly what these tests do: they build the declarations on a bare JVM. A command that
 * touched {@code Bukkit}, read a config or reached for the module's services would fail here, which is
 * the whole point.
 */
class ModerationCommandsTest {

    private final List<ModuleCommand> declared = ModerationCommands.declared();

    @Test
    @DisplayName("the commands can be declared with nothing running at all")
    void nothingIsNeededToDeclareThem() {
        assertThat(declared).isNotEmpty();
        assertThat(declared).allSatisfy(command -> assertThat(command.handler()).isNotNull());
    }

    @Test
    @DisplayName("the commands a moderator reaches for are all there")
    void theExpectedCommands() {
        List<String> names = new ArrayList<>();
        declared.forEach(command -> names.addAll(command.names()));

        assertThat(names).contains("ban", "tempban", "unban", "pardon", "mute", "unmute", "kick",
                "warn", "freeze", "unfreeze", "history", "vanish", "invsee", "report", "reports",
                "staffchat", "mod", "promote", "demote");
    }

    @Test
    @DisplayName("no two commands answer to the same word")
    void nothingCollidesWithItself() {
        // Two registrations of one name is one of them silently winning, and the loser is a command
        // that exists in the help and does nothing anybody expects.
        List<String> names = new ArrayList<>();
        declared.forEach(command -> names.addAll(command.names()));

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every command says what it is for")
    void everyCommandIsDescribed() {
        assertThat(declared).allSatisfy(command -> {
            assertThat(command.description()).isNotBlank();
            assertThat(command.handler()).isInstanceOf(IModerationCommand.class);
            assertThat(((IModerationCommand) command.handler()).describe()).isNotBlank();
        });
    }

    @Test
    @DisplayName("every command is guarded — by a permission node, or by being console-only")
    void everyCommandIsGuarded() {
        List<String> unguarded = new ArrayList<>();
        for (ModuleCommand command : declared) {
            String permission = command.handler().permission();
            boolean consoleOnly = command.handler() instanceof IModerationCommand mine
                    && mine.consoleOnly();
            if ((permission == null || permission.isBlank()) && !consoleOnly) {
                unguarded.add(command.name());
            }
        }

        assertThat(unguarded)
                .as("a moderation command with neither guard is one every player on the server can run")
                .isEmpty();
    }

    @Test
    @DisplayName("the console-only commands are the two that hand out protection, and no others")
    void onlyProtectionIsConsoleOnly() {
        // Console-only is the strongest guard there is here — a server cannot grant it to anybody —
        // and it is also the most annoying, so it belongs on exactly the commands that need it. If a
        // third one ever appears in this list, that is a decision somebody should have to write down.
        List<String> consoleOnly = declared.stream()
                .filter(command -> command.handler() instanceof IModerationCommand mine
                        && mine.consoleOnly())
                .map(ModuleCommand::name)
                .toList();

        assertThat(consoleOnly).containsExactlyInAnyOrder("protect", "unprotect");
    }

    @Test
    @DisplayName("protection is guarded by the console rather than by a node anybody could be granted")
    void protectionHasNoNode() {
        for (ModuleCommand command : declared) {
            if (command.handler() instanceof IModerationCommand mine && mine.consoleOnly()) {
                assertThat(command.handler().permission())
                        .as("%s has a permission node, which is a thing a server can grant — and "
                                + "granting the shield to the people it is aimed at is the whole "
                                + "failure it exists to prevent", command.name())
                        .isNull();
            }
        }
    }

    @Test
    @DisplayName("declaring them twice gives the same commands, because bootstrap may ask more than once")
    void declaringIsRepeatable() {
        List<String> first = new ArrayList<>();
        ModerationCommands.declared().forEach(command -> first.addAll(command.names()));
        List<String> second = new ArrayList<>();
        ModerationCommands.declared().forEach(command -> second.addAll(command.names()));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a command run while the module is not running says so rather than throwing null")
    void notRunningIsAnAnswer() {
        // The host wraps every command in ModuleCommands.guarded, so a player never reaches this. If
        // anything ever does, the message is the useful half — "not started" rather than a null
        // dereference forty frames deep in a menu.
        ModerationCommands.stopped();

        assertThat(ModerationCommands.isRunning()).isFalse();
    }
}
