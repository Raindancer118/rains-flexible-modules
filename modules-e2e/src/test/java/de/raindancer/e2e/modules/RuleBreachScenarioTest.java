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
 * The rules say what breaking them costs, everybody can read it, and moderation hands out exactly that — the
 * next rung for each offence — by command and from the player's page.
 */
@Tag("e2e")
class RuleBreachScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("players read each rule's punishment; /broke and the player page climb the rule's own ladder")
    void punishedByTheRules() {
        try (Server server = Server.start("rule-breach",
                List.of("moderation-standalone:RainsModeration-.*", "essentials-standalone:RainsEssentials-.*"),
                List.of("Moderation is up", "Essentials are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // Everybody reads what breaking a rule costs (friendly-smp: Be kind = warn, mute 1h, mute 1d, ban 3d).
            bo.forgetChat();
            bo.run("rules");
            Await.until(() -> "Bo reads the punishments (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "1st: a warning · 2nd: muted for 1 hour · 3rd: muted for 1 day · then: banned for 3 days"));

            ada.runAndExpect("broke Bo 1 called people names", "Bo broke rule 1 (Be kind) for the 1st time: a warning");
            ada.runAndExpect("broke Bo 1", "for the 2nd time: muted for 1 hour");
            bo.forgetChat();
            bo.say("can I still talk?");
            Await.until(() -> "Bo is muted (Bo heard " + bo.chatText() + ")", WAIT, () -> said(bo, "muted"));

            // The third time from the player's page, through the confirmation.
            ada.run("mod Bo");
            ada.click("Broke a rule");
            ada.awaitWindow("Which rule");
            Await.until("the page says what is next", WAIT, () -> ada.window()
                    .map(window -> window.items().values().stream().anyMatch(item -> item.lore().stream()
                            .anyMatch(line -> line.contains("Now: muted for 1 day")))).orElse(false));
            ada.click("1. Be kind");
            ada.click("Yes, do it");
            Await.until("the third rung is handed out", WAIT, () -> said(ada, "for the 3rd time: muted for 1 day"));

            // The owner changes what a rule costs; the next offence follows the new ladder.
            ada.runAndExpect("rules punishment 7 kick", "Breaking it now costs — every time: a kick");
            ada.runAndExpect("broke Bo 7 spam", "Bo broke rule 7 (Keep chat readable) for the 1st time: a kick");
            Await.until("Bo is kicked", WAIT, () -> !bo.isOnline());

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEssentials")).isEmpty();
        }
    }
}
