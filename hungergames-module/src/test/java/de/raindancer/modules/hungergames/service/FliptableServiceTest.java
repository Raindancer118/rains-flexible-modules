package de.raindancer.modules.hungergames.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FliptableService#deleteEverything} actually removing a tree from disk — the part
 * {@code TheFliptableKeepsTheConfigurationTest} does not reach, because that test lives beside
 * {@code HungerGamesCommands} and cannot see this package-private method.
 *
 * <h2>The incident this guards against</h2>
 * This server's own {@code world/} folder, half-deleted after a real {@code /fliptable confirm}:
 * {@code level.dat} and the overworld's dimension data were gone, but {@code world/data},
 * {@code world/datapacks} and {@code world/players} — untouched for days — were still there. The old
 * {@code deleteTree} walked the tree with a single {@code Files.walk} stream; the moment that stream's
 * terminal operation hit one entry it could not read, the {@code IOException} propagated out of the whole
 * walk and abandoned everything not yet visited. Paper's next boot found that half-world and refused to
 * start: {@code IllegalStateException: Overworld settings missing}. The server did not come back up.
 */
class FliptableServiceTest {

    @Test
    @DisplayName("a plain tree is gone completely")
    void aPlainTree(@TempDir Path scratch) throws IOException {
        Path world = scratch.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "x");
        Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "x");

        FliptableService.deleteEverything(List.of(world));

        assertThat(Files.exists(world)).isFalse();
    }

    @Nested
    @DisplayName("one entry the walk cannot read")
    class OneBadEntry {

        @Test
        @DisplayName("does not abandon the rest of the tree")
        void doesNotStopTheRest() throws IOException {
            Path scratch = Files.createTempDirectory("fliptable-partial-");
            Path world = scratch.resolve("world");
            Path locked = world.resolve("locked");
            Path after = world.resolve("after");
            try {
                Files.createDirectories(locked);
                Files.createDirectories(after);
                Files.writeString(locked.resolve("region.mca"), "x");
                Files.writeString(after.resolve("level.dat"), "x");

                // No read/execute — the walk cannot list what is inside, and (on the version this
                // guards against) that IOException used to abandon the entire walk rather than just
                // this one directory.
                assumeCanDenyOwnAccess(locked);

                try {
                    FliptableService.deleteEverything(List.of(world));

                    assertThat(Files.exists(after.resolve("level.dat")))
                            .as("everything after the unreadable entry must still be removed, not "
                                    + "abandoned the moment one entry could not be read")
                            .isFalse();
                } finally {
                    restoreAccess(locked);
                }
            } finally {
                restoreAccess(locked);
                FliptableService.deleteEverything(List.of(scratch));
            }
        }

        /** Skips the test rather than failing it where the owner cannot be denied access — often root. */
        private void assumeCanDenyOwnAccess(Path directory) {
            boolean denied = directory.toFile().setReadable(false)
                    && directory.toFile().setExecutable(false);
            Assumptions.assumeTrue(denied,
                    "this environment will not let its own owner be denied access to a directory "
                            + "(often true when tests run as root) — nothing to prove the fix against here");
        }

        private void restoreAccess(Path directory) {
            directory.toFile().setReadable(true);
            directory.toFile().setExecutable(true);
        }
    }
}
