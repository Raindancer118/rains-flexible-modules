package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.chat.NetworkChatSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** One public chat over two servers: what is said on one is shown on the other, tagged, and a mute holds on both. */
class NetworkChatServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** A link that records what is sent and lets a test play what arrives. */
    private static final class FakeLink implements ServerLink {
        final List<Map<String, String>> sent = new ArrayList<>();
        final Map<String, Function<Map<String, String>, Map<String, String>>> handlers = new java.util.HashMap<>();

        @Override
        public void send(String topic, Map<String, String> data) {
            sent.add(data);
        }

        @Override
        public CompletableFuture<Map<String, String>> request(String topic, Map<String, String> data, Duration timeout) {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public void on(String topic, Function<Map<String, String>, Map<String, String>> handler) {
            handlers.put(topic, handler);
        }

        @Override
        public void off(String topic) {
            handlers.remove(topic);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private final FakeLink link = new FakeLink();
    private final List<Component> shown = new ArrayList<>();
    private final Set<UUID> muted = new HashSet<>();
    private NetworkChatService network;

    @BeforeEach
    void setUp() {
        network = new NetworkChatService(() -> Optional.of(link), muted::contains, shown::add,
                (tag, line) -> Component.text("[" + tag + "] ").append(line),
                new NetworkChatSettings(true, "Arena"));
        network.listen();
    }

    @Test
    @DisplayName("a line said here goes to the other server with who said it, what they said and this server's tag")
    void saidIsSent() {
        UUID lilly = UUID.randomUUID();

        network.said(lilly, "Lilly", "gg", Component.text("Lilly: gg"));

        assertThat(link.sent).hasSize(1);
        assertThat(link.sent.getFirst()).containsEntry("uuid", lilly.toString()).containsEntry("name", "Lilly")
                .containsEntry("text", "gg").containsEntry("tag", "Arena").containsKey("line");
    }

    @Test
    @DisplayName("a line from the other server is shown here, as it was drawn there, behind its server's tag")
    void receivedIsShown() {
        network.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        Map<String, String> arrived = link.sent.getFirst();

        link.handlers.get(NetworkChatService.TOPIC).apply(arrived);

        assertThat(shown).extracting(PLAIN::serialize).containsExactly("[Arena] Lilly: gg");
    }

    @Test
    @DisplayName("somebody muted here is not heard here, whatever server they said it on")
    void mutedIsNotShown() {
        UUID mutedHere = UUID.randomUUID();
        muted.add(mutedHere);
        network.said(UUID.randomUUID(), "x", "y", Component.text("x: y"));
        Map<String, String> arrived = new java.util.HashMap<>(link.sent.getFirst());
        arrived.put("uuid", mutedHere.toString());

        link.handlers.get(NetworkChatService.TOPIC).apply(arrived);

        assertThat(shown).isEmpty();
    }

    @Test
    @DisplayName("switched off, nothing is sent and nothing that arrives is shown")
    void offMeansOff() {
        network.settings(new NetworkChatSettings(false, "Arena"));
        network.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        assertThat(link.sent).isEmpty();

        link.handlers.get(NetworkChatService.TOPIC).apply(Map.of("uuid", UUID.randomUUID().toString(),
                "name", "x", "text", "y", "tag", "SMP", "line", "{\"text\":\"x: y\"}"));
        assertThat(shown).isEmpty();
    }

    @Test
    @DisplayName("something that is not a chat line is dropped rather than shown half-read")
    void garbageDropped() {
        link.handlers.get(NetworkChatService.TOPIC).apply(Map.of("uuid", "not-a-uuid", "line", "{broken"));
        link.handlers.get(NetworkChatService.TOPIC).apply(Map.of());

        assertThat(shown).isEmpty();
    }

    @Test
    @DisplayName("without a link there is simply nobody to tell")
    void noLink() {
        NetworkChatService alone = new NetworkChatService(Optional::empty, muted::contains, shown::add,
                (tag, line) -> line, new NetworkChatSettings(true, "SMP"));

        alone.listen();
        alone.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));

        assertThat(shown).isEmpty();
    }

    @Test
    @DisplayName("stopping lets go of the topic")
    void stopReleases() {
        network.stop();

        assertThat(link.handlers).doesNotContainKey(NetworkChatService.TOPIC);
    }
}
