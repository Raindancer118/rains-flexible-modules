package de.raindancer.modules.chat.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The chat history as one document of the network: what is said anywhere is read everywhere. */
@Tag("database")
class ChatHistoryStoreNetworkTest {

    private static final long DAY = 86_400_000L;

    @TempDir
    Path plugins;

    private Servers servers;
    private final AtomicLong now = new AtomicLong(1_000 * DAY);
    private final UUID tom = UUID.randomUUID();

    private ChatHistoryStore on(String id) {
        if (servers == null) {
            servers = new Servers(plugins);
        }
        Documents documents = servers.server(id);
        ChatHistoryStore store = new ChatHistoryStore(servers.network(documents, "history.yml"), now::get);
        store.load();
        store.watch();
        return store;
    }

    @AfterEach
    void close() {
        if (servers != null) {
            servers.close();
        }
    }

    @Test
    @DisplayName("a line said on one server is in the history of the other once it is written")
    void linesTravel() {
        ChatHistoryStore smp = on("smp");
        ChatHistoryStore arena = on("arena");

        smp.record(tom, "Tom", "hello from the smp", now.get());
        assertThat(smp.flush()).isTrue();
        servers.heard();

        assertThat(arena.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("hello from the smp");
    }

    @Test
    @DisplayName("each server's lines stay when both write: nobody's line is lost to the other's write")
    void bothServersKeepTheirLines() {
        ChatHistoryStore smp = on("smp");
        ChatHistoryStore arena = on("arena");

        smp.record(tom, "Tom", "smp line", now.get());
        arena.record(tom, "Tom", "arena line", now.get() + 1);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        servers.heard();

        assertThat(smp.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("smp line", "arena line");
        assertThat(arena.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("smp line", "arena line");
    }

    @Test
    @DisplayName("a line not yet written survives the reload another server's write causes")
    void unwrittenLineSurvivesReload() {
        ChatHistoryStore smp = on("smp");
        ChatHistoryStore arena = on("arena");

        smp.record(tom, "Tom", "not written yet", now.get());
        arena.record(UUID.randomUUID(), "Bea", "from the arena", now.get() + 1);
        assertThat(arena.flush()).isTrue();
        servers.heard();

        assertThat(smp.lastLines(10)).extracting(ChatHistoryStore.Line::text)
                .containsExactly("not written yet", "from the arena");
        assertThat(smp.flush()).isTrue();
        servers.heard();
        assertThat(arena.lastLines(10)).extracting(ChatHistoryStore.Line::text)
                .containsExactly("not written yet", "from the arena");
    }

    @Test
    @DisplayName("leaving on one server is what the other measures 'what did I miss' from")
    void lastQuitTravels() {
        ChatHistoryStore smp = on("smp");
        ChatHistoryStore arena = on("arena");

        smp.markQuit(tom, now.get() - 5_000L);
        smp.flush();
        servers.heard();

        assertThat(arena.lastQuit(tom)).contains(now.get() - 5_000L);
    }

    @Test
    @DisplayName("names beside chat lines are kept for the retention and no longer, in memory and in the document")
    void retentionPrunes() {
        ChatHistoryStore smp = on("smp");
        smp.retention(30);
        smp.record(tom, "Tom", "old", now.get() - 31 * DAY);
        smp.record(tom, "Tom", "recent", now.get() - 29 * DAY);
        smp.markQuit(tom, now.get() - 40 * DAY);
        UUID bea = UUID.randomUUID();
        smp.markQuit(bea, now.get() - DAY);

        assertThat(smp.flush()).isTrue();

        assertThat(smp.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("recent");
        assertThat(smp.lastQuit(tom)).isEmpty();
        assertThat(smp.lastQuit(bea)).isPresent();
        ChatHistoryStore fresh = on("arena");
        assertThat(fresh.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("recent");
        assertThat(fresh.lastQuit(tom)).isEmpty();
    }

    @Test
    @DisplayName("what is already in the document past the retention is dropped on load")
    void retentionAppliesOnLoad() {
        ChatHistoryStore smp = on("smp");
        smp.retention(730);
        smp.record(tom, "Tom", "ancient", now.get() - 500 * DAY);
        smp.flush();

        ChatHistoryStore arena = on("arena");
        arena.retention(100);
        arena.load();

        assertThat(arena.lastLines(10)).isEmpty();
    }

    @Test
    @DisplayName("the retention is at least a day and at most two years")
    void retentionIsClamped() {
        ChatHistoryStore smp = on("smp");
        assertThat(smp.retention(0)).isEqualTo(1);
        assertThat(smp.retention(5000)).isEqualTo(730);
    }

    @Test
    @DisplayName("the 1.x list of lines is read, and written over in the new shape")
    void legacyListIsRead() throws Exception {
        servers = new Servers(plugins);
        Documents documents = servers.server("smp");
        YamlStore raw = servers.network(documents, "history.yml");
        raw.write(yaml -> {
            yaml.set("lines", java.util.List.of(java.util.Map.of("sender", tom.toString(), "name", "Tom",
                    "text", "from 1.x", "at", now.get() - DAY)));
            yaml.set("last-quit." + tom, now.get() - DAY);
        });

        ChatHistoryStore store = new ChatHistoryStore(servers.network(documents, "history.yml"), now::get);
        store.load();
        assertThat(store.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("from 1.x");

        assertThat(store.flush()).isTrue();
        ChatHistoryStore again = new ChatHistoryStore(servers.network(documents, "history.yml"), now::get);
        again.load();
        assertThat(again.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("from 1.x");
        assertThat(raw.read().isList("lines")).isFalse();
    }

    @Test
    @DisplayName("a database that cannot be read does not empty the history or take what was said: it keeps it and says no")
    void failedReadKeepsWhatIsHeld() throws Exception {
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        try {
            Documents documents = new Documents(broken, null, plugins, "smp", System::currentTimeMillis);
            ChatHistoryStore store = new ChatHistoryStore(
                    new YamlStore(plugins.resolve("RainsChat").resolve("history.yml"), documents, Documents.Scope.NETWORK),
                    now::get);

            store.load();
            store.record(tom, "Tom", "still here", now.get());

            assertThat(store.flush()).isFalse();
            assertThat(store.lastLines(10)).extracting(ChatHistoryStore.Line::text).containsExactly("still here");
        } finally {
            broken.close();
        }
    }
}
