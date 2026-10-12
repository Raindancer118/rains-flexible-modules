package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /chat say from the console, and the panel feed, on a real server — what the Pelican chat page uses. */
@Tag("e2e")
class ChatSayScenarioTest {

    @Test
    @DisplayName("the console speaks as a player who left; everybody sees it; the feed holds both kinds of line")
    void saysAsSomebody() throws Exception {
        try (Server server = Server.start("chat-say", List.of("chat-standalone:RainsChat-.*"), List.of("Chat is up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");
            ada.say("typed in game");
            Await.ticks(10);
            ada.leave();
            Await.ticks(20);
            bo.forgetChat();

            server.paper.console("chat say Ada hello from the panel");
            Await.until("Bo sees Ada's line", Duration.ofSeconds(10),
                    () -> bo.chatText().stream().anyMatch(line -> line.contains("hello from the panel")));
            assertThat(bo.chatText()).anyMatch(line -> line.contains("Ada") && line.contains("hello from the panel"));

            assertThat(server.paper.console("chat say Nobody hi")).contains("Nobody");

            var feed = server.paper.file("plugins/RainsChat/panel-feed.json");
            Await.until("the feed is written", Duration.ofSeconds(10), () -> {
                try {
                    return Files.isRegularFile(feed) && Files.readString(feed).contains("hello from the panel");
                } catch (Exception e) {
                    return false;
                }
            });
            String written = Files.readString(feed);
            assertThat(written).contains("typed in game").contains("\"panel\":true").contains("\"panel\":false");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsChat")).isEmpty();
        }
    }
}
