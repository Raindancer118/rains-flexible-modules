package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A rule whose ladder carries fines: "warn + fine 100", "fine 200", "mute 1h + fine 50", each charged by /broke. */
@Tag("e2e")
class RuleFineScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void balanceIs(Bot bot, String shown) {
        bot.forgetChat();
        bot.run("balance");
        Await.until(bot.name() + " has " + shown, WAIT, () -> said(bot, "You have " + shown));
    }

    @Test
    @DisplayName("each rung's fine is charged on top of what the rung does")
    void ruleFines() {
        try (Server server = Server.start("rule-fine",
                List.of("economy-standalone:RainsEconomy-.*", "moderation-standalone:RainsModeration-.*",
                        "essentials-standalone:RainsEssentials-.*"),
                List.of("The economy is up", "Moderation is up", "Essentials are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            balanceIs(bo, "⛃1,000");

            ada.runAndExpect("rules punishment 7 warn + fine 100, fine 200, mute 1h + fine 50", "Breaking it now costs");

            ada.runAndExpect("broke Bo 7 spam", "for the 1st time: a warning and a fine of 100");
            balanceIs(bo, "⛃900");
            ada.runAndExpect("broke Bo 7 spam", "for the 2nd time: a fine of 200");
            balanceIs(bo, "⛃700");
            ada.runAndExpect("broke Bo 7 spam", "for the 3rd time: muted for 1 hour and a fine of 50");
            balanceIs(bo, "⛃650");
            bo.forgetChat();
            bo.say("am I muted?");
            Await.until(() -> "Bo is muted (Bo heard " + bo.chatText() + ")", WAIT, () -> said(bo, "muted"));

            ada.forgetChat();
            ada.run("history Bo");
            Await.until("the record lists the fines", WAIT, () -> said(ada, "fined"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEconomy", "RainsEssentials")).isEmpty();
        }
    }
}
