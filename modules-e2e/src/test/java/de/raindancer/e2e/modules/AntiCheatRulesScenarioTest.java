package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
class AntiCheatRulesScenarioTest {

    private static final double FLOOR = 101.0;

    private static void tick(Bot bot, Runnable sends) {
        sends.run();
        bot.tickEnd();
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @DisplayName("a cheater at a check's ban level gets the 'No cheating' rule's first punishment, counted like a moderator's")
    void punishedByTheRules() {
        try (Server server = Server.start("anticheat-rules",
                List.of("anticheat-standalone:RainsAntiCheat-.*", "moderation-standalone:RainsModeration-.*",
                        "essentials-standalone:RainsEssentials-.*"),
                List.of("Anti-cheat is up", "Moderation is up", "Essentials are up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:stone");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            assertThat(server.console("rules")).as("a new server starts with the friendly-smp rules").contains("No cheating");
            // Straight to the ban level: no kick on the way, and a level a few hits reach.
            assertThat(server.console("settings set auto-kick false")).contains("is now");
            assertThat(server.console("settings set punish-scale 10")).contains("is now");

            Bot ada = server.admin("Ada");
            Bot target = server.player("Target");
            Bot multi = server.player("Multi");
            server.console("tp Ada -1.5 " + FLOOR + " 1.5 0 0");
            server.console("tp Target 0.5 " + FLOOR + " 0.3 0 0");
            server.console("tp Multi 1.2 " + FLOOR + " 1.2 0 0");
            Await.ticks(80);   // past the join and teleport grace
            ada.forgetChat();

            Await.until("Multi is thrown out by the rules", Duration.ofSeconds(40), () -> {
                if (!multi.isOnline()) {
                    return true;
                }
                for (int t = 0; t < 5; t++) {
                    tick(multi, () -> { });
                }
                tick(multi, () -> multi.attack(target.id()).attack(ada.id()).swing());
                return !multi.isOnline();
            });

            Await.until("staff are told which rule was broken", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("broke rule") && line.contains("No cheating")));
            List<String> chat = ada.chatText();
            assertThat(chat).anyMatch(line -> line.contains("Multi") && line.contains("1st offence")
                    && line.contains("banned for 14 days"));
            String history = server.console("history Multi");
            assertThat(history).as("recorded like a moderator's, under the rule").contains("No cheating");
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat", "RainsModeration", "RainsEssentials")).isEmpty();
        }
    }
}
