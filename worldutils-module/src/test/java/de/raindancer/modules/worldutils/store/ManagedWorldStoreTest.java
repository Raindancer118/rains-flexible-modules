package de.raindancer.modules.worldutils.store;

import de.raindancer.modules.worldutils.model.ManagedWorld;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedWorldStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a world made here survives a restart, with the dimension it was made as")
    void survivesARestart() {
        ManagedWorldStore store = new ManagedWorldStore(folder);
        store.load();
        assertThat(store.add(new ManagedWorld("farm_nether", World.Environment.NETHER))).isTrue();
        assertThat(store.add(new ManagedWorld("farm", World.Environment.NORMAL))).isTrue();

        ManagedWorldStore reopened = new ManagedWorldStore(folder);

        assertThat(reopened.load()).containsExactlyInAnyOrder(
                new ManagedWorld("farm_nether", World.Environment.NETHER),
                new ManagedWorld("farm", World.Environment.NORMAL));
        assertThat(reopened.isManaged("FARM")).isTrue();
    }

    @Test
    @DisplayName("a deleted world is forgotten on disk too")
    void forgetting() {
        ManagedWorldStore store = new ManagedWorldStore(folder);
        store.add(new ManagedWorld("farm", World.Environment.NORMAL));
        store.remove("farm");

        assertThat(new ManagedWorldStore(folder).load()).isEmpty();
    }

    @Test
    @DisplayName("an environment that is not one is not loaded as the wrong dimension")
    void aBrokenLine() throws Exception {
        Files.writeString(folder.resolve("worlds.yml"), """
                worlds:
                  good:
                    environment: THE_END
                  bad:
                    environment: SKY
                """);

        assertThat(new ManagedWorldStore(folder).load())
                .containsExactly(new ManagedWorld("good", World.Environment.THE_END));
    }
}
