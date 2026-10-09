package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /invsee on somebody offline whom Paper's name cache has forgotten — the case that said "nobody called that
 * has ever joined" on a live server. Forgotten for real: the server is stopped and {@code usercache.json} loses
 * them before it starts again. Also: the moderation page shows playtime.
 */
@Tag("e2e")
class InvseeOfflineScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    @DisplayName("an offline player the name cache forgot is still found by /invsee, in any case, with their items")
    void forgottenByTheCache() {
        try (Server server = Server.start("invsee-offline", List.of("moderation-standalone:RainsModeration-.*"),
                List.of())) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            server.console("give Bo diamond 7");
            Await.until("Bo holds diamonds", WAIT, () -> bo.carrying(item -> item.is("diamond")).isPresent());

            // The moderation page shows how long they have played.
            ada.run("mod Bo");
            Await.until(() -> "the moderation page has a playtime clock (window " + ada.window() + ")", WAIT,
                    () -> ada.window().flatMap(window -> window.slotNamed("Playtime")).isPresent());
            ada.closeWindow();

            bo.leave();
            Await.ticks(40);
            ada.runAndOpen("invsee Bo", "Bo");
            ada.closeWindow();

            // Restart with Bo gone from the server's name cache.
            server.paper.stop();
            forget(server.paper.folder().resolve("usercache.json"), "Bo");
            server.paper.start();
            server.paper.awaitLog("Moderation", WAIT);
            Bot again = server.player("Ada");
            server.console("op Ada");
            Await.ticks(10);

            for (String typed : List.of("Bo", "bo")) {
                again.forgetChat();
                again.run("invsee " + typed);
                Await.until(() -> "Ada sees Bo's inventory for /invsee " + typed + " (told " + again.chatText() + ")",
                        WAIT, () -> again.window().map(window -> window.title().contains("Bo")).orElse(false));
                assertThat(again.window().orElseThrow().items().values()).anyMatch(item -> item.is("diamond"));
                assertThat(again.chatText()).noneMatch(line -> line.contains("has ever joined"));
                again.closeWindow();
                Await.ticks(10);
            }
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }

    /** Takes one name out of usercache.json, the way an expired or evicted entry would be missing. */
    private static void forget(Path usercache, String name) {
        try {
            String json = Files.readString(usercache);
            assertThat(json).contains("\"" + name + "\"");
            String without = json.replaceAll("\\{[^{}]*\"name\"\\s*:\\s*\"" + name + "\"[^{}]*},?", "")
                    .replace(",]", "]");
            Files.writeString(usercache, without);
            assertThat(Files.readString(usercache)).doesNotContain("\"" + name + "\"");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
