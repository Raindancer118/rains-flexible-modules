package de.raindancer.modules.voicebridge.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LinkStoreTest {

    @TempDir
    Path folder;

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    @Test
    @DisplayName("a link is found from either side, and survives a restart")
    void persists() {
        LinkStore store = new LinkStore(folder.resolve("links.yml"));
        store.link(123L, alex);

        LinkStore reopened = new LinkStore(folder.resolve("links.yml"));
        assertThat(reopened.playerOf(123L)).contains(alex);
        assertThat(reopened.discordOf(alex)).contains(123L);
    }

    @Test
    @DisplayName("one Discord account is one player and the other way round: relinking replaces")
    void oneToOne() {
        LinkStore store = new LinkStore(folder.resolve("links.yml"));
        store.link(123L, alex);
        store.link(123L, sam);
        assertThat(store.playerOf(123L)).contains(sam);
        assertThat(store.discordOf(alex)).isEmpty();

        store.link(456L, sam);
        assertThat(store.playerOf(123L)).isEmpty();
        assertThat(store.discordOf(sam)).contains(456L);
    }

    @Test
    @DisplayName("unlinking forgets both directions, on disk too")
    void unlink() {
        LinkStore store = new LinkStore(folder.resolve("links.yml"));
        store.link(123L, alex);

        assertThat(store.unlink(alex)).isTrue();
        assertThat(store.unlink(alex)).isFalse();
        assertThat(new LinkStore(folder.resolve("links.yml")).playerOf(123L)).isEmpty();
    }

    @Test
    @DisplayName("no file is no links, not an error")
    void empty() {
        assertThat(new LinkStore(folder.resolve("links.yml")).playerOf(1L)).isEmpty();
    }
}
