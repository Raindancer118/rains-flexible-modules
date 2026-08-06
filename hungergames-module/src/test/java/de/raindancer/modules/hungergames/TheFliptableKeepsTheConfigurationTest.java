package de.raindancer.modules.hungergames;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.hungergames.service.FliptableService;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code /fliptable} — and the property that matters is what it <em>leaves</em>.
 *
 * <h2>Why the interesting assertions are about survival</h2>
 * A test that a delete deletes is easy to write and nearly worthless: the failure mode nobody recovers from
 * is the one where it takes the evening's tuning with it. {@code config.yml}, {@code loot.yml}, the gamemaster
 * roster and the arena are hours of somebody's work, they are not part of a round, and a reset that eats them
 * is one nobody dares run — after which the server is reset by hand at eleven at night, which is the thing
 * this command exists to stop.
 *
 * <p>So the headline test asserts that {@link FliptableService#wouldDelete} names none of
 * {@link FliptableService#KEPT_FILES}, and it reads that list from the class rather than repeating it, so a
 * file added to what must survive is covered without anybody remembering to come back here.
 */
class TheFliptableKeepsTheConfigurationTest {

    @Nested
    @DisplayName("the word that has to be typed")
    class TheConfirmation {

        @Test
        @DisplayName("only 'confirm' counts")
        void exactlyTheWord() {
            assertThat(FliptableService.isConfirmed(new String[] {"confirm"})).isTrue();
            assertThat(FliptableService.isConfirmed(new String[] {"CONFIRM"}))
                    .as("shouting it is still typing it")
                    .isTrue();
            assertThat(FliptableService.isConfirmed(new String[] {"  confirm  "}))
                    .as("a pasted line brings its own whitespace")
                    .isTrue();
        }

        @Test
        @DisplayName("nothing that merely looks like it counts")
        void nothingElse() {
            // A prefix match is the shape of this guard failing: /fliptable c would delete the server.
            List<String[]> refused = List.of(
                    new String[0],
                    new String[] {""},
                    new String[] {"c"},
                    new String[] {"con"},
                    new String[] {"yes"},
                    new String[] {"y"},
                    new String[] {"please"},
                    new String[] {"confirmed"},
                    new String[] {"confirmation"},
                    new String[] {"reset", "confirm"});

            for (String[] typed : refused) {
                assertThat(FliptableService.isConfirmed(typed))
                        .as("'%s' must not be read as the confirmation", String.join(" ", typed))
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a null argument list is refused rather than thrown at")
        void nulls() {
            assertThat(FliptableService.isConfirmed(null)).isFalse();
            assertThat(FliptableService.isConfirmed(new String[] {null})).isFalse();
        }
    }

    @Nested
    @DisplayName("the blast radius")
    class WhatWouldGo {

        @Test
        @DisplayName("the four state files and the world folders, and nothing beside them")
        void exactlyThose() {
            Path data = Path.of("plugins", "TheHungerGames");
            List<Path> worlds = List.of(Path.of("world"), Path.of("world_nether"), Path.of("world_the_end"));

            List<Path> doomed = FliptableService.wouldDelete(worlds, data);

            assertThat(doomed)
                    .as("the round's state: %s", FliptableService.STATE_FILES)
                    .containsAll(FliptableService.STATE_FILES.stream().map(data::resolve).toList());
            assertThat(doomed)
                    .containsAll(worlds.stream().map(one -> one.toAbsolutePath().normalize()).toList());
            assertThat(doomed).hasSize(FliptableService.STATE_FILES.size() + worlds.size());
        }

        @Test
        @DisplayName("none of the files that must survive is on it")
        void theConfigurationSurvives() {
            Path data = Path.of("plugins", "TheHungerGames");

            List<Path> doomed = FliptableService.wouldDelete(List.of(Path.of("world")), data);

            List<String> taken = new ArrayList<>();
            for (String kept : FliptableService.KEPT_FILES) {
                if (doomed.contains(data.resolve(kept))) {
                    taken.add(kept);
                }
            }
            assertThat(taken)
                    .as("these are the evening's tuning, not the round's state — a reset that takes them "
                            + "is one nobody runs, and then the server is reset by hand instead")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two lists do not overlap, so neither can drift into the other")
        void keptAndDoomedAreDisjoint() {
            assertThat(FliptableService.KEPT_FILES).doesNotContainAnyElementsOf(FliptableService.STATE_FILES);
        }

        @Test
        @DisplayName("with no data folder there is nothing to delete in one")
        void noDataFolder() {
            assertThat(FliptableService.wouldDelete(List.of(Path.of("world")), null))
                    .containsExactly(Path.of("world").toAbsolutePath().normalize());
        }

        @Test
        @DisplayName("a world folder inside another is not listed twice")
        void theDimensionsLayout() {
            // Paper 26 puts a world created after the overworld at <level-name>/dimensions/<ns>/<name>.
            // Listed on its own it would be walked after its parent had already gone, and reported as a
            // folder that could not be deleted — the one line somebody reads after a reset, saying the
            // opposite of what happened.
            Path overworld = Path.of("world");
            Path nested = Path.of("world", "dimensions", "minecraft", "arena");

            List<Path> doomed = FliptableService.wouldDelete(List.of(overworld, nested), null);

            assertThat(doomed).containsExactly(overworld.toAbsolutePath().normalize());
        }

        @Test
        @DisplayName("the same world named twice is deleted once")
        void duplicates() {
            assertThat(FliptableService.wouldDelete(
                            List.of(Path.of("world"), Path.of("./world")), null))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("arming")
    class Arming {

        @Test
        @DisplayName("a second confirmation adds no second shutdown hook")
        void onlyOnce(@TempDir Path scratch) {
            // Deliberately a temporary folder JUnit removes: the hook registered here really does run at the
            // end of this JVM, and it must find nothing to do.
            LogChannel log = mock(LogChannel.class);

            assertThat(FliptableService.armFor(List.of(scratch.resolve("world")), scratch, log))
                    .as("the first confirmation arms it")
                    .isTrue();
            assertThat(FliptableService.isArmed()).isTrue();
            assertThat(FliptableService.armFor(List.of(scratch.resolve("world")), scratch, log))
                    .as("two hooks walking one directory tree is how a delete half-finishes and reports "
                            + "success")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the command itself")
    class TheSurface {

        private static ModuleCommand fliptable() {
            return HungerGamesCommands.declared().stream()
                    .filter(one -> one.name().equals("fliptable"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no /fliptable in declared() — a handler that is not declared at bootstrap "
                                    + "never runs at all, silently"));
        }

        @Test
        @DisplayName("it is declared at bootstrap, where commands actually come from")
        void itIsDeclared() {
            HungerGamesCommands.forget();

            assertThat(fliptable().description()).isNotBlank();
        }

        @Test
        @DisplayName("it is an admin's, not a gamemaster's")
        void theNode() {
            assertThat(fliptable().permission())
                    .as("a guest gamemaster runs the round; deleting the server it ran on is not part of "
                            + "running it")
                    .isEqualTo(PermissionNodes.ADMIN);
        }

        @Test
        @DisplayName("tab completion does not offer the confirmation")
        void theGuardIsNotCompleted() {
            HungerGamesCommands.forget();

            // The typed word is the guard. Completing it means passing it with two keystrokes without ever
            // reading the warning it exists for.
            assertThat(fliptable().handler().suggest(null, new String[] {"con"}))
                    .doesNotContain(FliptableService.CONFIRMATION);
        }
    }
}
