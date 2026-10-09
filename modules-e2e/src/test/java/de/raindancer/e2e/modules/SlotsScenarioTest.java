package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Buying an extra home slot and an extra claim slot on a real server, at prices set in the settings. */
@Tag("e2e")
class SlotsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void expect(Bot bot, String what, String text) {
        try {
            Await.until(what, WAIT, () -> said(bot, text));
        } catch (AssertionError failed) {
            throw new AssertionError(failed.getMessage() + " — " + bot.name() + " saw " + bot.chatText(), failed);
        }
    }

    private static void set(Server server, String key, String value) {
        assertThat(server.console("settings set " + key + " " + value)).contains("is now");
        Await.ticks(20);
    }

    @Test
    @DisplayName("a home slot bought at the limit, and a claim slot bought by command, at their set prices")
    void slots() {
        try (Server server = Server.start("slots", List.of("economy-standalone:RainsEconomy-.*",
                "homes-standalone:RainsHomes-.*", "claims-standalone:RainsExtendedClaims-.*"),
                List.of("The economy is up"))) {
            Await.ticks(60);
            Bot bo = server.player("Bo");

            // ---- switched off by default: a full player is simply full, a slot cannot be bought
            set(server, "homes.max", "1");
            bo.run("sethome a");
            Await.ticks(20);
            bo.forgetChat();
            bo.run("claim buyslot");
            expect(bo, "claim slots are off by default", "switched off");

            // ---- home slots: on, priced at 100
            set(server, "homes.slot-buying", "true");
            set(server, "homes.slot-price", "100");
            bo.forgetChat();
            bo.run("sethome b");
            expect(bo, "Bo is offered a slot at his limit", "Another slot costs ⛃100");
            bo.awaitWindow("Buy a home slot?");
            int yes = Await.value("the Yes button", WAIT, () -> bo.window().flatMap(window -> window.top().entrySet()
                    .stream().filter(entry -> entry.getValue().name().contains("Yes"))
                    .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
            bo.clickSlot(yes);
            expect(bo, "the slot is bought", "Bought an extra home slot");
            bo.forgetChat();
            bo.run("balance");
            expect(bo, "100 left his account", "You have ⛃900");

            // ---- claim slots: on, priced at 50, bought by command after a confirmation
            set(server, "slots.buying", "true");
            set(server, "slots.price", "50");
            bo.forgetChat();
            bo.run("claim buyslot");
            expect(bo, "the price is said first", "One more claim costs ⛃50");
            bo.run("claim buyslot confirm");
            expect(bo, "the claim slot is bought", "Bought a claim slot");
            bo.forgetChat();
            bo.run("balance");
            expect(bo, "50 more left his account", "You have ⛃850");
        }
    }
}
