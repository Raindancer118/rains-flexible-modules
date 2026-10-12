package de.raindancer.modules.arena.store;

import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Layout;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StoresTest {

    @TempDir
    Path folder;

    private static Layout full() {
        return Layout.EMPTY
                .withWorld(new ArenaWorld("arena", "coliseum.schem", new Box("arena", -115, 64, -98, 115, 127, 97)))
                .withWorld(new ArenaWorld("arena_2", "colosseum.schem", new Box("arena_2", -59, 64, -56, 58, 102, 56)))
                .withHub(new Place("arena", 0.5, 71, -120.5, 180f, 0f))
                .withPit(Pit.named("main", "arena").withFloor(new Box("arena", -40, 64, -30, 40, 90, 30))
                        .withSpawn(new Place("arena", -30, 65, 0, -90f, 0f))
                        .withSpawn(new Place("arena", 30, 65, 0, 90f, 0f)))
                .withPit(Pit.named("second", "arena_2"));
    }

    @Test
    @DisplayName("the layout comes back exactly as it was written — worlds, hub, pits, spawns, unfinished pits too")
    void layoutRoundTrip() {
        LayoutStore store = new LayoutStore(folder.resolve("layout.yml"));

        assertThat(store.save(full())).isTrue();

        assertThat(new LayoutStore(folder.resolve("layout.yml")).load()).isEqualTo(full());
    }

    @Test
    @DisplayName("no file yet is an empty layout, not an error")
    void noFile() {
        assertThat(new LayoutStore(folder.resolve("layout.yml")).load()).isEqualTo(Layout.EMPTY);
    }

    @Test
    @DisplayName("a broken entry costs that entry and leaves the rest standing")
    void brokenEntrySkipped() throws IOException {
        Path file = folder.resolve("layout.yml");
        new LayoutStore(file).save(full());
        String text = Files.readString(file).replace("map: colosseum.schem", "map: ''\n    box: nonsense");
        Files.writeString(file, text);

        Layout read = new LayoutStore(file).load();

        assertThat(read.worlds()).extracting(ArenaWorld::name).containsExactly("arena");
        assertThat(read.pits()).hasSize(2);
        assertThat(read.hub()).isPresent();
    }
}
