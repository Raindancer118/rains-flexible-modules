package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rent: a landlord asks, the tenant accepts, both see the contract, and the tenant can end it. */
@Tag("e2e")
class EconomyContractScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("a landlord charges rent; nothing starts until the tenant accepts; either side can end it")
    void rent() {
        try (Server server = Server.start("economy-contract", List.of("economy-standalone:RainsEconomy-.*"),
                List.of("The economy is up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");

            ada.run("contract charge Bo 300 1d Apartment 3");
            Await.until(() -> "Bo is asked to pay rent (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "asks you to pay them ⛃300 every 1 day for Apartment 3"));
            assertThat(said(ada, "Waiting for an answer")).isTrue();

            bo.clickButtonOn("asks you to pay them", 0);
            Await.until("both are told", WAIT, () -> said(bo, "you pay Ada ⛃300 every 1 day for Apartment 3")
                    && said(ada, "Bo pays you ⛃300 every 1 day for Apartment 3"));

            ada.run("contract");
            ada.awaitWindow("Jobs");
            assertThat(ada.window().orElseThrow().slotNamed("Bo pays you for Apartment 3")).isPresent();
            ada.closeWindow();

            bo.forgetChat();
            bo.run("contract end Ada");
            Await.until("the tenant ends it", WAIT, () -> said(bo, "You ended the contract with Ada for Apartment 3")
                    && said(ada, "Bo ended the contract for Apartment 3"));
            ada.run("contract");
            ada.awaitWindow("Jobs");
            assertThat(ada.window().orElseThrow().slotNamed("Apartment 3")).isEmpty();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy")).isEmpty();
        }
    }
}
