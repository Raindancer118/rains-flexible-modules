package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.store.ChatHistoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryServiceTest {

    private final Path dataFolder = Path.of("target", "test-chat-history-service");
    private final AtomicLong now = new AtomicLong(0L);
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

    private ChatHistoryService service(ChatSettings settings) {
        return new ChatHistoryService(store, settings, now::get);
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("does nothing when history is switched off")
        void recordsNothingWhenDisabled() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, true, true, 70, 8,
                    true, 0, 0, false, 200, true);
            ChatHistoryService service = service(off);

            service.record(UUID.randomUUID(), "Tom", "hello");

            assertThat(service.recent(10)).isEmpty();
        }

        @Test
        @DisplayName("shows up in recent() once written")
        void recordsWhenEnabled() {
            ChatHistoryService service = service(ChatSettings.DEFAULTS);

            service.record(UUID.randomUUID(), "Tom", "hello there");

            assertThat(service.recent(10)).extracting(ChatHistoryStore.Line::text)
                    .containsExactly("hello there");
        }
    }

    @Nested
    @DisplayName("what was missed")
    class Missed {

        @Test
        @DisplayName("only lines after the player's last quit are returned")
        void onlyAfterLastQuit() {
            ChatHistoryService service = service(ChatSettings.DEFAULTS);
            UUID tom = UUID.randomUUID();
            UUID alex = UUID.randomUUID();

            now.set(1_000L);
            service.record(alex, "Alex", "before tom left");
            service.markLeft(tom);
            now.set(2_000L);
            service.record(alex, "Alex", "while tom was gone");

            List<ChatHistoryStore.Line> missed = service.missedBy(tom);

            assertThat(missed).extracting(ChatHistoryStore.Line::text)
                    .containsExactly("while tom was gone");
        }

        @Test
        @DisplayName("a player who has never left has nothing missed")
        void neverLeftMeansNothingMissed() {
            ChatHistoryService service = service(ChatSettings.DEFAULTS);
            service.record(UUID.randomUUID(), "Alex", "hello");

            assertThat(service.missedBy(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("is empty when history is switched off, even with a recorded quit")
        void emptyWhenDisabled() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, true, true, 70, 8,
                    true, 0, 0, false, 200, true);
            ChatHistoryService service = service(off);
            UUID tom = UUID.randomUUID();
            service.markLeft(tom);

            assertThat(service.missedBy(tom)).isEmpty();
        }
    }

    @Nested
    @DisplayName("notifying on join")
    class NotifyOnJoin {

        @Test
        @DisplayName("follows the setting when history is on")
        void followsSetting() {
            ChatSettings notified = new ChatSettings("<name>: <message>", true, true, true, true, 70,
                    8, true, 0, 0, true, 200, false);

            assertThat(service(ChatSettings.DEFAULTS).notifyOnJoin()).isTrue();
            assertThat(service(notified).notifyOnJoin()).isFalse();
        }

        @Test
        @DisplayName("is always false when history itself is off")
        void offWhenHistoryOff() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, true, true, 70, 8,
                    true, 0, 0, false, 200, true);

            assertThat(service(off).notifyOnJoin()).isFalse();
        }
    }
}
