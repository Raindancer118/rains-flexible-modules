package de.raindancer.modules.speedrun.manhunt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manhunt's files moving into the speedrun plugin's folder on the first start of the merged plugin —
 * with real files, in both layouts a live server can have.
 */
class ManhuntMigrationTest {

    @TempDir
    Path plugins;

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Every file the old plugin kept, each with content of its own. */
    private static void oldManhunt(Path folder) throws IOException {
        write(folder.resolve("config.yml"), "runner-lives: 3\n");
        write(folder.resolve("stats.yml"), "players: {}\n");
        write(folder.resolve("hunts.yml"), "hunts: {}\n");
        write(folder.resolve("whitelist-state.yml"), "closed-by-hunt: true\n");
        write(folder.resolve("whitelist-vips.yml"), "vips: {}\n");
        write(folder.resolve("setup.yml"), "done: true\n");
    }

    @Test
    @DisplayName("standalone: plugins/RainsManhunt/… moves into plugins/RainsSpeedrun/, config.yml becoming manhunt.yml")
    void standaloneLayout() throws IOException {
        Path old = plugins.resolve("RainsManhunt");
        Path speedrun = plugins.resolve("RainsSpeedrun");
        oldManhunt(old);
        Files.createDirectories(speedrun);

        ManhuntMigration.Result result = ManhuntMigration.run(speedrun);

        assertThat(Files.readString(speedrun.resolve("manhunt.yml"))).isEqualTo("runner-lives: 3\n");
        assertThat(Files.readString(speedrun.resolve("stats.yml"))).isEqualTo("players: {}\n");
        assertThat(Files.readString(speedrun.resolve("hunts.yml"))).isEqualTo("hunts: {}\n");
        assertThat(Files.readString(speedrun.resolve("whitelist-state.yml"))).isEqualTo("closed-by-hunt: true\n");
        assertThat(Files.readString(speedrun.resolve("whitelist-vips.yml"))).isEqualTo("vips: {}\n");
        assertThat(Files.readString(speedrun.resolve("setup-manhunt.yml"))).isEqualTo("done: true\n");
        assertThat(result.moved()).hasSize(6);
        assertThat(result.from()).contains(old);
    }

    @Test
    @DisplayName("bundle: plugins/RainsSpeedrunServer/modules/manhunt/… moves into …/modules/speedrun/")
    void bundleLayout() throws IOException {
        Path modules = plugins.resolve("RainsSpeedrunServer").resolve("modules");
        Path old = modules.resolve("manhunt");
        Path speedrun = modules.resolve("speedrun");
        oldManhunt(old);
        Files.createDirectories(speedrun);

        ManhuntMigration.run(speedrun);

        assertThat(Files.readString(speedrun.resolve("manhunt.yml"))).isEqualTo("runner-lives: 3\n");
        assertThat(Files.exists(speedrun.resolve("hunts.yml"))).isTrue();
    }

    @Test
    @DisplayName("every original is kept as a backup in the new folder, and only then removed from the old one")
    void backupBeforeRemoval() throws IOException {
        Path old = plugins.resolve("RainsManhunt");
        Path speedrun = plugins.resolve("RainsSpeedrun");
        oldManhunt(old);
        Files.createDirectories(speedrun);

        ManhuntMigration.run(speedrun);

        Path backup = speedrun.resolve(ManhuntMigration.BACKUP_FOLDER).resolve("RainsManhunt");
        assertThat(Files.readString(backup.resolve("config.yml"))).isEqualTo("runner-lives: 3\n");
        assertThat(Files.readString(backup.resolve("stats.yml"))).isEqualTo("players: {}\n");
        assertThat(Files.exists(old.resolve("config.yml"))).isFalse();
        assertThat(Files.exists(old.resolve("stats.yml"))).isFalse();
        assertThat(Files.readString(old.resolve(ManhuntMigration.NOTE))).contains("RainsSpeedrun");
    }

    @Test
    @DisplayName("a file already in the new folder is never overwritten — the old one stays where it was")
    void neverOverwrites() throws IOException {
        Path old = plugins.resolve("RainsManhunt");
        Path speedrun = plugins.resolve("RainsSpeedrun");
        oldManhunt(old);
        write(speedrun.resolve("stats.yml"), "players: {already: here}\n");

        ManhuntMigration.Result result = ManhuntMigration.run(speedrun);

        assertThat(Files.readString(speedrun.resolve("stats.yml"))).isEqualTo("players: {already: here}\n");
        assertThat(Files.readString(old.resolve("stats.yml"))).isEqualTo("players: {}\n");
        assertThat(result.kept()).containsExactly(old.resolve("stats.yml"));
        assertThat(Files.readString(speedrun.resolve("manhunt.yml"))).isEqualTo("runner-lives: 3\n");
    }

    @Test
    @DisplayName("a second start finds nothing left to move and changes nothing")
    void runsOnce() throws IOException {
        Path old = plugins.resolve("RainsManhunt");
        Path speedrun = plugins.resolve("RainsSpeedrun");
        oldManhunt(old);
        Files.createDirectories(speedrun);
        ManhuntMigration.run(speedrun);
        Files.writeString(speedrun.resolve("manhunt.yml"), "runner-lives: 5\n");

        ManhuntMigration.Result again = ManhuntMigration.run(speedrun);

        assertThat(again.moved()).isEmpty();
        assertThat(Files.readString(speedrun.resolve("manhunt.yml"))).isEqualTo("runner-lives: 5\n");
    }

    @Test
    @DisplayName("a server that never had Manhunt is left exactly as it is")
    void nothingToDo() throws IOException {
        Path speedrun = plugins.resolve("RainsSpeedrun");
        Files.createDirectories(speedrun);

        ManhuntMigration.Result result = ManhuntMigration.run(speedrun);

        assertThat(result.moved()).isEmpty();
        try (Stream<Path> files = Files.list(speedrun)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("a file the old folder holds that Manhunt never wrote is left alone")
    void foreignFilesStay() throws IOException {
        Path old = plugins.resolve("RainsManhunt");
        Path speedrun = plugins.resolve("RainsSpeedrun");
        oldManhunt(old);
        write(old.resolve("notes.txt"), "the owner's own");
        Files.createDirectories(speedrun);

        ManhuntMigration.run(speedrun);

        assertThat(Files.readString(old.resolve("notes.txt"))).isEqualTo("the owner's own");
        assertThat(Files.exists(speedrun.resolve("notes.txt"))).isFalse();
    }
}
