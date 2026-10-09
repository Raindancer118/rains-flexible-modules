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
 * Vanishing on a server with essentials' own join and quit lines reads as exactly those lines — vanilla's yellow
 * "left the game" was the tell — and a vanished player's real quit and rejoin say nothing at all.
 */
@Tag("e2e")
class VanishLinesScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean saw(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static boolean sawVanilla(Bot bot) {
        return bot.chatText().stream().anyMatch(line -> line.contains("left the game")
                || line.contains("joined the game") || line.contains("multiplayer.player"));
    }

    @Test
    @DisplayName("vanish says essentials' quit line, coming back its join line; a vanished quit and rejoin say nothing")
    void customLines() {
        try (Server server = Server.start("vanish-lines",
                List.of("moderation-standalone:RainsModeration-.*", "essentials-standalone:RainsEssentials-.*"),
                List.of("Moderation is up", "Essentials are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Await.ticks(20);

            bo.forgetChat();
            ada.run("vanish");
            Await.until(() -> "Bo is told Ada left, essentials' way (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> saw(bo, "- Ada left."));
            assertThat(sawVanilla(bo)).as("no vanilla line beside it").isFalse();

            bo.forgetChat();
            ada.run("vanish");
            Await.until(() -> "Bo is told Ada joined (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> saw(bo, "+ Ada joined."));
            assertThat(sawVanilla(bo)).isFalse();

            // Vanished, then really leaving and coming back: nobody is told anything.
            ada.run("vanish");
            Await.ticks(20);
            bo.forgetChat();
            ada.leave();
            Await.ticks(40);
            Bot again = server.player("Ada");
            Await.ticks(40);
            assertThat(bo.chatText()).as("a vanished player's quit and rejoin are nobody's business")
                    .noneMatch(line -> line.contains("Ada"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEssentials")).isEmpty();
            again.run("vanish");
        }
    }
}
