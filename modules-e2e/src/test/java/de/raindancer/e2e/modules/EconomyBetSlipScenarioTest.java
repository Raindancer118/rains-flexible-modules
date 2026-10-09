package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A typed bet stays typed (crash reset it to its opening bet), and any game goes all in, uncapped. */
@Tag("e2e")
class EconomyBetSlipScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static Optional<Integer> slip(Bot bot, String text) {
        return bot.window().flatMap(window -> window.slotNamed(text));
    }

    @Test
    @DisplayName("crash keeps a typed bet; right-clicking the bet slip goes all in, however much that is")
    void typedAndAllIn() {
        try (Server server = Server.start("economy-bets",
                List.of("economy-standalone:RainsEconomy-.*"), List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            ada.run("eco give Ada 4999000");

            ada.run("crash");
            ada.awaitWindow("Crash");
            Await.until("crash takes bets", Duration.ofSeconds(60), () -> slip(ada, "Bet:").isPresent());
            ada.clickSlot(slip(ada, "Bet:").orElseThrow());
            ada.typeInAnvil("700");
            Await.until("the crash slip says 700 — not the opening bet", WAIT,
                    () -> slip(ada, "Bet: ⛃700").isPresent());

            ada.rightClickSlot(slip(ada, "Bet:").orElseThrow());
            Await.until("all in is all five million", WAIT, () -> slip(ada, "Bet: ⛃5,000,000").isPresent());
            ada.closeWindow();

            // The same slip in a card game, with no largest bet to stop it.
            ada.run("blackjack");
            ada.awaitWindow("Blackjack");
            ada.rightClickSlot(slip(ada, "Bet:").orElseThrow());
            Await.until("blackjack goes all in", WAIT, () -> slip(ada, "Bet: ⛃5,000,000").isPresent());

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy")).isEmpty();
        }
    }
}
