package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.store.GamemasterStore;
import de.raindancer.modules.hungergames.store.GamemasterStore.ActiveState;
import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link GamemasterStore}: what a restart needs to hand a gamemaster their previous mode back. */
class GamemasterStoreTest {

    @Test
    @DisplayName("active gamemasters survive a save and a load")
    void roundTrip(@TempDir Path dir) {
        GamemasterStore store = new GamemasterStore(dir.resolve("gamemasters.yml"));
        UUID gm = UUID.randomUUID();

        store.save(Map.of(gm, new ActiveState(GameMode.SURVIVAL, true)));

        Map<UUID, ActiveState> loaded = store.load();
        assertThat(loaded).containsEntry(gm, new ActiveState(GameMode.SURVIVAL, true));
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("no file yet is an empty roster, not an exception")
    void missingFile(@TempDir Path dir) {
        GamemasterStore store = new GamemasterStore(dir.resolve("gamemasters.yml"));

        assertThat(store.load()).isEmpty();
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("a corrupt file is reported and quarantined, not overwritten in place")
    void corruptFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gamemasters.yml");
        Files.writeString(file, "active: [broken: [[[");
        GamemasterStore store = new GamemasterStore(file);

        assertThat(store.load()).isEmpty();
        assertThat(store.problems()).isNotEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("an entry with an unreadable game mode is skipped, not fatal to the rest")
    void skipsOneBrokenEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gamemasters.yml");
        UUID good = UUID.randomUUID();
        Files.writeString(file, """
                active:
                  not-a-uuid:
                    previous-mode: SURVIVAL
                    deopped: false
                  %s:
                    previous-mode: CREATIVE
                    deopped: true
                """.formatted(good));
        GamemasterStore store = new GamemasterStore(file);

        Map<UUID, ActiveState> loaded = store.load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded).containsEntry(good, new ActiveState(GameMode.CREATIVE, true));
        assertThat(store.problems()).isNotEmpty();
    }
}
