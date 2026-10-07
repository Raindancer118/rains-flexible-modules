package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Speaking while vanished: held, warned about, and sent only when the player says so. */
@Tag("e2e")
class VanishChatScenarioTest {

    @Test
    @DisplayName("a vanished player's chat line and /msg are held until they click Send anyway")
    void heldUntilConfirmed() {
        try (Server server = Server.start("vanishchat",
                List.of("moderation-standalone:RainsModeration-.*", "essentials-standalone:RainsEssentials-.*"),
                List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            ada.run("vanish");
            Await.ticks(20);

            ada.forgetChat();
            ada.say("hello from the shadows");
            Await.until("Ada is warned", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("you're vanished")));
            Await.ticks(10);
            assertThat(server.paper.logLines(line -> line.contains("hello from the shadows"))).isEmpty();

            ada.clickButtonOn("Send anyway", 0);
            Await.until("the line went out", Duration.ofSeconds(10),
                    () -> !server.paper.logLines(line -> line.contains("hello from the shadows")).isEmpty());

            bo.forgetChat();
            ada.forgetChat();
            ada.run("msg Bo psst");
            Await.until("Ada is warned about /msg", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("you're vanished")));
            Await.ticks(10);
            assertThat(bo.chatText()).noneMatch(line -> line.contains("psst"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEssentials")).isEmpty();
        }
    }
}
