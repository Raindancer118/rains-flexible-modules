package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fines on a real server with the real economy: charging, debt, forgiving, revoking, warnings that cost, a mute bought off. */
@Tag("e2e")
class FineScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void balanceIs(Bot bot, String shown) {
        bot.forgetChat();
        bot.run("balance");
        Await.until(bot.name() + " has " + shown, WAIT, () -> said(bot, "You have " + shown));
    }

    @Test
    @DisplayName("a fine is charged, the rest becomes debt, forgiving and revoking work, warnings can cost, a mute can be bought off")
    void fines() {
        try (Server server = Server.start("fines",
                List.of("economy-standalone:RainsEconomy-.*", "moderation-standalone:RainsModeration-.*"),
                List.of("The economy is up", "Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            balanceIs(bo, "⛃1,000");

            // ---- a fine, paid in full
            ada.forgetChat();
            bo.forgetChat();
            ada.run("fine Bo 250 griefing");
            Await.until("Ada is told", WAIT, () -> said(ada, "was fined"));
            Await.until("Bo is told", WAIT, () -> said(bo, "You have been fined"));
            balanceIs(bo, "⛃750");
            ada.forgetChat();
            ada.run("history Bo");
            Await.until("the record shows the amount", WAIT, () -> said(ada, "fined") && said(ada, "250"));

            // ---- one the account cannot cover: taken as far as it goes, the rest is debt
            ada.forgetChat();
            ada.run("fine Bo 2000 greed");
            Await.until("Ada hears about the debt", WAIT, () -> said(ada, "becomes debt"));
            balanceIs(bo, "⛃0");
            bo.forgetChat();
            bo.run("debt");
            Await.until("Bo sees what he owes", WAIT, () -> said(bo, "You owe") && said(bo, "1,250"));

            // ---- staff can forgive it
            ada.forgetChat();
            ada.run("fine forgive Bo");
            Await.until("Ada sees it written off", WAIT, () -> said(ada, "written off"));
            bo.forgetChat();
            bo.run("debt");
            Await.until("Bo owes nothing", WAIT, () -> said(bo, "You owe nothing"));

            // ---- revoking gives back what was paid: the 750 of the second fine, then the 250
            ada.run("fine revoke Bo");
            Await.until("the second fine is revoked", WAIT, () -> said(ada, "is revoked"));
            balanceIs(bo, "⛃750");

            // ---- a warning that costs money
            assertThat(server.console("settings set punishments.warn-fine 50, 100")).contains("is now");
            ada.run("warn Bo spamming");
            Await.until("Bo is told the warning cost money", WAIT, () -> said(bo, "That warning cost you"));
            balanceIs(bo, "⛃700");
            bo.forgetChat();
            ada.run("warn Bo spamming again");
            Await.until("the second one costs the next rung", WAIT, () -> said(bo, "That warning cost you"));
            balanceIs(bo, "⛃600");

            // ---- a mute bought off
            assertThat(server.console("settings set punishments.mute-buyoff-per-hour 10")).contains("is now");
            ada.run("mute Bo 2h swearing");
            Await.until("Bo is muted", WAIT, () -> said(bo, "You have been muted"));
            bo.forgetChat();
            bo.run("buyoff");
            Await.until("Bo is quoted", WAIT, () -> said(bo, "costs") && said(bo, "buyoff confirm"));
            bo.forgetChat();
            bo.run("buyoff confirm");
            Await.until("Bo paid", WAIT, () -> said(bo, "may talk again"));
            balanceIs(bo, "⛃580");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEconomy")).isEmpty();
        }
    }
}
