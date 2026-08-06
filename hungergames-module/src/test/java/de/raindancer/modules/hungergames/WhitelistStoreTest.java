package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.store.WhitelistStore;
import de.raindancer.modules.hungergames.store.WhitelistStore.LegacyEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link WhitelistStore}: reading the old v1 whitelist.yml, once, for a service to migrate. */
class WhitelistStoreTest {

    @Test
    @DisplayName("map-shaped rows are read with their team and dead flag")
    void readsMapRows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("whitelist.yml");
        Files.writeString(file, """
                players:
                  - name: "Anna"
                    team: 2
                    dead: true
                  - name: "Bela"
                """);
        WhitelistStore store = new WhitelistStore(file);

        List<LegacyEntry> entries = store.readLegacy();

        assertThat(entries).containsExactly(
                new LegacyEntry("Anna", 2, true),
                new LegacyEntry("Bela", 0, false));
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("bare string rows are read as an alive, teamless entry")
    void readsPlainStringRows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("whitelist.yml");
        Files.writeString(file, """
                players:
                  - "Cleo"
                """);
        WhitelistStore store = new WhitelistStore(file);

        assertThat(store.readLegacy()).containsExactly(new LegacyEntry("Cleo", 0, false));
    }

    @Test
    @DisplayName("no file yet is an empty list, not an exception")
    void missingFile(@TempDir Path dir) {
        WhitelistStore store = new WhitelistStore(dir.resolve("whitelist.yml"));

        assertThat(store.readLegacy()).isEmpty();
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("a corrupt file is reported and quarantined rather than retried forever")
    void corruptFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("whitelist.yml");
        Files.writeString(file, "players: [broken: [[[");
        WhitelistStore store = new WhitelistStore(file);

        assertThat(store.readLegacy()).isEmpty();
        assertThat(store.problems()).isNotEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("an entry without a name is skipped, not fatal to the rest of the list")
    void skipsUnnamedEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("whitelist.yml");
        Files.writeString(file, """
                players:
                  - team: 1
                  - name: "Divo"
                """);
        WhitelistStore store = new WhitelistStore(file);

        List<LegacyEntry> entries = store.readLegacy();

        assertThat(entries).containsExactly(new LegacyEntry("Divo", 0, false));
        assertThat(store.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("an empty players list is an empty result")
    void emptyPlayersList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("whitelist.yml");
        Files.writeString(file, "players: []\n");
        WhitelistStore store = new WhitelistStore(file);

        assertThat(store.readLegacy()).isEmpty();
    }
}
