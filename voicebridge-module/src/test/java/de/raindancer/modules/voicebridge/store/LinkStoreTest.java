package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class LinkStoreTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    private LinkStore server(String id) {
        Database database = TestDatabases.open(folder.resolve("voicebridge.db"), LinkStore.SCHEMA);
        databases.add(database);
        LocalBus bus = hub.bus(id);
        LinkStore store = new LinkStore(database, bus, null);
        open.add(store.connect(bus));
        return store;
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable each : open) {
            each.close();
        }
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a link is found from either side, and survives a restart")
    void persists() {
        server("smp").link(123L, alex);

        LinkStore reopened = new LinkStore(databases.getFirst(), null, null);
        assertThat(reopened.playerOf(123L)).contains(alex);
        assertThat(reopened.discordOf(alex)).contains(123L);
    }

    @Test
    @DisplayName("one Discord account is one player and the other way round: relinking replaces")
    void oneToOne() {
        LinkStore store = server("smp");
        store.link(123L, alex);
        store.link(123L, sam);
        assertThat(store.playerOf(123L)).contains(sam);
        assertThat(store.discordOf(alex)).isEmpty();

        store.link(456L, sam);
        assertThat(store.playerOf(123L)).isEmpty();
        assertThat(store.discordOf(sam)).contains(456L);
    }

    @Test
    @DisplayName("unlinking forgets both directions, in the database too")
    void unlink() {
        LinkStore store = server("smp");
        store.link(123L, alex);

        assertThat(store.unlink(alex)).isTrue();
        assertThat(store.unlink(alex)).isFalse();
        assertThat(new LinkStore(databases.getFirst(), null, null).playerOf(123L)).isEmpty();
    }

    @Test
    @DisplayName("a discord id beyond 32 bits is kept whole")
    void bigIds() {
        long snowflake = 1_234_567_890_123_456_789L;
        server("smp").link(snowflake, alex);
        assertThat(new LinkStore(databases.getFirst(), null, null).discordOf(alex)).contains(snowflake);
    }

    @Test
    @DisplayName("a player linked on one server is linked on the other, and an unlink follows too")
    void followsThePlayer() {
        LinkStore smp = server("smp");
        LinkStore arena = server("arena");

        smp.link(123L, alex);
        hub.drain();
        assertThat(arena.discordOf(alex)).contains(123L);
        assertThat(arena.playerOf(123L)).contains(alex);

        arena.unlink(alex);
        hub.drain();
        assertThat(smp.discordOf(alex)).isEmpty();
    }

    @Test
    @DisplayName("a store that cannot read the database keeps what it knew")
    void unreadableKeepsWhatItKnew() throws Exception {
        Path broken = folder.resolve("elsewhere").resolve("voicebridge.db");
        Files.createDirectories(broken.getParent());
        Database down = TestDatabases.unusable(broken, LinkStore.SCHEMA);
        try {
            LinkStore store = new LinkStore(down, null, null);
            assertThat(store.link(1L, alex)).as("nothing could be stored").isFalse();
            assertThat(store.playerOf(1L)).isEmpty();
        } finally {
            down.close();
        }
    }

    @Test
    @DisplayName("the 1.x links.yml is brought in once; what is linked already wins; an unlink is not undone at restart")
    void importsOldLinks() throws Exception {
        Path file = folder.resolve("links.yml");
        Files.writeString(file, "links:\n  '123': " + alex + "\n  '456': " + sam + "\n  'nonsense': x\n");
        Database database = TestDatabases.open(folder.resolve("voicebridge.db"), LinkStore.SCHEMA);
        databases.add(database);
        new LinkStore(database, null, null).link(456L, alex);

        LinkStore store = new LinkStore(database, null, new YamlStore(file));
        assertThat(store.playerOf(123L)).as("alex is linked to 456 already: the old 123 does not add a second").isEmpty();
        assertThat(store.playerOf(456L)).contains(alex);

        store.unlink(alex);
        LinkStore again = new LinkStore(database, null, new YamlStore(file));
        assertThat(again.playerOf(456L)).isEmpty();
        assertThat(again.playerOf(123L)).isEmpty();
    }

    @Test
    @DisplayName("the 1.x links come across whole when nothing is linked yet")
    void importsIntoEmpty() throws Exception {
        Path file = folder.resolve("links.yml");
        Files.writeString(file, "links:\n  '123': " + alex + "\n  '456': " + sam + "\n");
        Database database = TestDatabases.open(folder.resolve("voicebridge.db"), LinkStore.SCHEMA);
        databases.add(database);

        LinkStore store = new LinkStore(database, null, new YamlStore(file));
        assertThat(store.playerOf(123L)).contains(alex);
        assertThat(store.discordOf(sam)).contains(456L);
    }
}
