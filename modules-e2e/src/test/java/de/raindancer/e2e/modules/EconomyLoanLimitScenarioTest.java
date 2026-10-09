package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every player's own loan limit: what they have, their record — and paying back on time raising it. */
@Tag("e2e")
class EconomyLoanLimitScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("a newcomer borrows up to what they have; a loan paid back on time raises the next limit")
    void personalLimit() {
        try (Server server = Server.start("economy-loan-limit", List.of("economy-standalone:RainsEconomy-.*"),
                List.of("The economy is up"))) {
            Bot bo = server.player("Bo");

            bo.run("loan take 2000");
            Await.until(() -> "over his own limit is refused (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "trusts you with ⛃1,000"));

            bo.run("loan");
            bo.awaitWindow("Loan");
            Await.until("the screen shows the limit", WAIT,
                    () -> bo.window().flatMap(window -> window.slotNamed("Your limit: ⛃1,000")).isPresent());
            bo.closeWindow();

            bo.forgetChat();
            bo.run("loan take 1000");
            Await.until("up to the limit is lent", WAIT, () -> said(bo, "You borrowed ⛃1,000"));
            bo.forgetChat();
            bo.run("loan repay");
            Await.until("paid off on time", WAIT, () -> said(bo, "paid off"));

            // 900 left, ×1.1 for one loan paid back on time.
            bo.forgetChat();
            bo.run("loan take 5000");
            Await.until(() -> "the record raised the limit (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "trusts you with ⛃990"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy")).isEmpty();
        }
    }
}
