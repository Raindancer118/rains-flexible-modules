package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @-mentions on a real server: by nickname, online and offline — an offline player is told when they are back.
 */
@Tag("e2e")
class MentionScenarioTest {

    @Test
    @DisplayName("a nickname mention pings; a mention of somebody offline waits for them")
    void mentions() {
        try (Server server = Server.start("mentions",
                List.of("essentials-standalone:RainsEssentials-.*", "chat-standalone:RainsChat-.*"),
                List.of("Chat is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            ada.runAndExpect("nick Bo Bobby", "Bobby");
            bo.forgetChat();
            ada.say("hey @Bobby, look at this");
            Await.until("Bo is pinged by nickname", Duration.ofSeconds(10),
                    () -> bo.chatText().stream().anyMatch(line -> line.contains("mentioned you")));

            bo.leave();
            Await.ticks(20);
            ada.say("where did @Bobby go?");
            Await.ticks(10);
            Bot back = server.player("Bo");
            Await.until("Bo hears about it on the way back in", Duration.ofSeconds(15),
                    () -> back.chatText().stream().anyMatch(line -> line.contains("While you were away")));
            assertThat(back.chatText()).anyMatch(line -> line.contains("where did @Bobby go?"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsChat")).isEmpty();
        }
    }
}
