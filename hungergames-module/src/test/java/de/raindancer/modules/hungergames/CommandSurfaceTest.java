package de.raindancer.modules.hungergames;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this module puts in front of somebody who types a slash.
 *
 * <h2>Why this is testable at all, and why that matters</h2>
 * {@link HungerGamesCommands#declared()} is called by Paper during <em>bootstrap</em> — before the plugin
 * object exists, before {@code Bukkit.getServer()} answers anything useful, and long before this module has
 * built a single service. That is a hard constraint rather than a detail: a handler registered in
 * {@code onEnable} never runs at all, with no warning and no exception, and the command simply is not there.
 *
 * <p>So {@code declared()} must be cheap, repeatable and dependent on nothing — which is exactly what makes
 * it callable from a test with no server anywhere near it. If it ever stops being callable here, it has
 * stopped being callable at bootstrap too, and the first symptom on a real server is a plugin whose commands
 * are silently absent.
 */
class CommandSurfaceTest {

    private static List<ModuleCommand> declared() {
        return HungerGamesCommands.declared();
    }

    @Nested
    @DisplayName("what bootstrap gets")
    class AtBootstrap {

        @Test
        @DisplayName("the declaration needs no server and no services")
        void nothingIsTouched() {
            // The whole contract. Constructed with the services deliberately absent — which is the real
            // state at bootstrap — and it must still produce the full list.
            HungerGamesCommands.forget();

            assertThat(declared())
                    .as("a declaration that needed anything built would produce no commands at all on a "
                            + "real server, silently")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("asking twice gives the same answer")
        void repeatable() {
            // Paper may ask more than once. A declaration that accumulated, or that consumed something,
            // would give every command a duplicate and every name a collision the second time.
            List<String> first = declared().stream().map(ModuleCommand::name).toList();
            List<String> second = declared().stream().map(ModuleCommand::name).toList();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a command run before the module started says so rather than throwing a null")
        void beforeTheModuleIsUp() {
            HungerGamesCommands.forget();

            // Driven through /allow's tab completion rather than through execute(), because execute()
            // reads the sender off the CommandSourceStack first and a null one throws before the supplier
            // is ever reached — which is correct ordering and simply not what this test is about.
            var allow = declared().stream()
                    .filter(one -> one.name().equals("allow"))
                    .findFirst().orElseThrow();

            // The host wraps these in ModuleCommands.guarded, so in practice this is what that guard
            // reports — but it has to be a sentence naming the module, not a NullPointerException out of a
            // field nobody can see.
            assertThatThrownBy(() -> allow.handler().suggest(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }

    @Nested
    @DisplayName("the names")
    class Names {

        @Test
        @DisplayName("the run-up is there, in the order it is typed")
        void theSequence() {
            List<String> names = declared().stream().map(ModuleCommand::name).toList();

            assertThat(names)
                    .as("these three are a sequence and are typed in order; they are also the part a "
                            + "console can do, so a scripted server can build its arena with nobody on")
                    .containsSubsequence("init", "startup", "start");
        }

        @Test
        @DisplayName("/st still works, because it is what a gamemaster's fingers know")
        void theShortcutSurvives() {
            // The old plugin registered it in plugin.yml and it was left behind in the port. A gamemaster who
            // has opened three hundred rounds with /st types it under pressure, gets "unknown command", and
            // is then reading a red line instead of watching a countdown.
            var start = declared().stream()
                    .filter(one -> one.name().equals("start"))
                    .findFirst().orElseThrow();

            assertThat(start.names())
                    .as("the aliases /start answers to: %s", start.names())
                    .contains("st");
        }

        @Test
        @DisplayName("the door and the list are there too")
        void theRest() {
            List<String> names = declared().stream().map(ModuleCommand::name).toList();

            assertThat(names).contains("hg", "allow");
        }

        @Test
        @DisplayName("no two commands want the same name or alias")
        void nothingCollidesWithItself() {
            Set<String> taken = new LinkedHashSet<>();
            List<String> clashes = new ArrayList<>();

            for (ModuleCommand command : declared()) {
                for (String name : command.names()) {
                    if (!taken.add(name)) {
                        clashes.add(name);
                    }
                }
            }
            assertThat(clashes)
                    .as("the second registration of a name replaces the first inside one plugin: one "
                            + "command silently answers the other's code")
                    .isEmpty();
        }

        @Test
        @DisplayName("the list is short, because a tournament is run by clicking")
        void deliberatelyFew() {
            // Twenty-four screens and five commands. If this grows a lot, something that should have been a
            // page has become a verb — and a gamemaster with forty people waiting has to spell it.
            assertThat(declared())
                    .as("what earns a command here is what a menu cannot do: somebody not online yet, a "
                            + "sequence typed in order, or a console with no inventory")
                    .hasSizeLessThanOrEqualTo(8);
        }
    }

    @Nested
    @DisplayName("who may run what")
    class Permissions {

        @Test
        @DisplayName("the arena is an admin's and the round is a gamemaster's")
        void theSplitIsKept() {
            var byName = declared().stream()
                    .collect(java.util.stream.Collectors.toMap(ModuleCommand::name, one -> one));

            assertThat(byName.get("init").permission())
                    .as("building the arena rebuilds the tournament; that is not a guest gamemaster's")
                    .isEqualTo(PermissionNodes.ADMIN);
            assertThat(byName.get("start").permission())
                    .as("running the round is exactly what a gamemaster is for")
                    .isEqualTo(PermissionNodes.GAMEMASTER);
            assertThat(byName.get("allow").permission()).isEqualTo(PermissionNodes.GAMEMASTER);
        }

        @Test
        @DisplayName("/hg itself is open to everybody")
        void theDoorIsNotLocked() {
            var hg = declared().stream().filter(one -> one.name().equals("hg")).findFirst().orElseThrow();

            // /hg teams and /hg shop are a player's. A permission on the root would put the whole command
            // behind a node only staff hold, so tributes could not pick a team — and the subcommands that
            // are staff-only check for themselves.
            assertThat(hg.permission()).isNullOrEmpty();
        }

        @Test
        @DisplayName("every permission named is one the module actually registers")
        void nothingAsksForANodeThatDoesNotExist() {
            List<String> real = PermissionNodes.declared().stream()
                    .map(permission -> permission.getName())
                    .toList();

            for (ModuleCommand command : declared()) {
                String node = command.permission();
                if (node == null || node.isBlank()) {
                    continue;
                }
                assertThat(real)
                        .as("/%s asks for '%s', which is not a node this module registers — so it "
                                + "resolves to operators-only and every gamemaster is refused",
                                command.name(), node)
                        .contains(node);
            }
        }
    }

    @Nested
    @DisplayName("what somebody reads")
    class Help {

        @Test
        @DisplayName("every command describes itself")
        void nothingIsUnexplained() {
            for (ModuleCommand command : declared()) {
                assertThat(command.description())
                        .as("/%s has no description, so the /commands directory lists it as a blank",
                                command.name())
                        .isNotBlank()
                        .hasSizeGreaterThan(10);
            }
        }

        @Test
        @DisplayName("the ones that take arguments say what they are")
        void theUsageIsThere() {
            var byName = declared().stream()
                    .collect(java.util.stream.Collectors.toMap(ModuleCommand::name, one -> one));

            assertThat(byName.get("allow").options())
                    .as("/allow takes names, and its whole point is that they need not be online — "
                            + "somebody has to be told that rather than discovering it")
                    .isNotEmpty();
            assertThat(byName.get("hg").options()).isNotEmpty();
        }
    }
}
