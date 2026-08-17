package de.raindancer.modules.chat.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryStoreTest {

    private final Path dataFolder = Path.of("target", "test-chat-history");
    private ChatHistoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dataFolder);
        store = new ChatHistoryStore(dataFolder);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> files = Files.walk(dataFolder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }

    @Test
    @DisplayName("lines after a moment are returned oldest first, and nothing earlier leaks in")
    void linesAfterFilters() {
        UUID tom = UUID.randomUUID();
        store.record(tom, "Tom", "first", 1_000L);
        store.record(tom, "Tom", "second", 2_000L);
        store.record(tom, "Tom", "third", 3_000L);

        List<ChatHistoryStore.Line> found = store.linesAfter(1_500L);

        assertThat(found).extracting(ChatHistoryStore.Line::text).containsExactly("second", "third");
    }

    @Test
    @DisplayName("capacity drops the oldest line once it is exceeded")
    void capacityDropsOldest() {
        UUID tom = UUID.randomUUID();
        store.capacity(2);
        store.record(tom, "Tom", "first", 1_000L);
        store.record(tom, "Tom", "second", 2_000L);
        store.record(tom, "Tom", "third", 3_000L);

        List<ChatHistoryStore.Line> found = store.lastLines(10);

        assertThat(found).extracting(ChatHistoryStore.Line::text).containsExactly("second", "third");
    }

    @Test
    @DisplayName("lastLines never returns more than there actually are")
    void lastLinesNeverOverflows() {
        UUID tom = UUID.randomUUID();
        store.record(tom, "Tom", "only one", 1_000L);

        List<ChatHistoryStore.Line> found = store.lastLines(50);

        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("last-quit is remembered per player, and unknown players have none")
    void lastQuitRoundTrips() {
        UUID tom = UUID.randomUUID();

        assertThat(store.lastQuit(tom)).isEmpty();

        store.markQuit(tom, 5_000L);

        assertThat(store.lastQuit(tom)).contains(5_000L);
    }

    @Test
    @DisplayName("lines and last-quit both survive a flush and a fresh load")
    void survivesRestart() {
        UUID tom = UUID.randomUUID();
        store.record(tom, "Tom", "hello there", 1_000L);
        store.markQuit(tom, 2_000L);

        assertThat(store.flush()).isTrue();

        ChatHistoryStore reloaded = new ChatHistoryStore(dataFolder);
        reloaded.load();

        assertThat(reloaded.lastLines(10)).extracting(ChatHistoryStore.Line::text)
                .containsExactly("hello there");
        assertThat(reloaded.lastQuit(tom)).contains(2_000L);
    }
}
