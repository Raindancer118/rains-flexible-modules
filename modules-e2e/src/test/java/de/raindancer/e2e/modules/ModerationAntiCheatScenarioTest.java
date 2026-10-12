package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Moderation and the anti-cheat side by side: a player's page shows what the checks saw of them. */
@Tag("e2e")
class ModerationAntiCheatScenarioTest {

    private static final double FLOOR = 101;

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static int slotNamed(Bot bot, String text) {
        return Await.value("a button named " + text, Duration.ofSeconds(15), () -> bot.window().flatMap(window -> window.top()
                .entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                .map(Map.Entry::getKey).findFirst()).orElse(null));
    }

    @Test
    @DisplayName("a fly cheater's page in /mod shows their anti-cheat level, worst check and replays")
    void pageShowsTheChecks() {
        try (Server server = Server.start("moderation-anticheat",
                List.of("moderation-standalone:RainsModeration-.*", "anticheat-standalone:RainsAntiCheat-.*"),
                List.of("Moderation is up", "Anti-cheat is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:stone");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            Bot ada = server.admin("Ada");
            Bot hover = server.player("Hover");
            server.console("tp Hover 3.5 " + FLOOR + " 0.5 0 0");
            Await.ticks(40);
            for (int t = 0; t < 80; t++) {
                hover.moveTo(3.5, FLOOR + 2 + t * 0.02, 0.5, false).tickEnd();
                sleep(50);
            }
            Await.until("the anti-cheat has a record of Hover", Duration.ofSeconds(15),
                    () -> server.console("anticheat log Hover").contains("Fly"));

            ada.run("mod Hover");
            ada.awaitWindow("Hover");
            ada.clickSlot(slotNamed(ada, "Look into"));
            ada.awaitWindow("Look into");
            int door = slotNamed(ada, "Anti-cheat");
            Bot.Item item = ada.window().orElseThrow().top().get(door);
            String lore = String.join(" / ", item.lore());
            assertThat(lore).contains("Level").contains("Fly").contains("replay");
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsAntiCheat")).isEmpty();
        }
    }

    @Test
    @DisplayName("hitting two players in one tick, three times, files a combat report straight into the queue")
    void combatReport() {
        try (Server server = Server.start("moderation-anticheat-combat",
                List.of("moderation-standalone:RainsModeration-.*", "anticheat-standalone:RainsAntiCheat-.*"),
                List.of("Moderation is up", "Anti-cheat is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:stone");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            Bot ada = server.admin("Ada");
            Bot multi = server.player("Multi");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");
            server.console("tp Multi 0.5 " + FLOOR + " 0.5 0 0");
            server.console("tp Bo 0.5 " + FLOOR + " 2.0 0 0");
            server.console("tp Cy 1.5 " + FLOOR + " 2.0 0 0");
            Await.ticks(40);
            for (int round = 0; round < 3; round++) {
                for (int t = 0; t < 5; t++) {
                    multi.tickEnd();
                    sleep(50);
                }
                multi.attack(bo.id()).attack(cy.id()).swing();
                multi.tickEnd();
                sleep(600);
            }
            Await.until("a combat report is waiting", Duration.ofSeconds(20),
                    () -> server.console("reports list").contains("combat"));
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsAntiCheat")).isEmpty();
        }
    }
}
