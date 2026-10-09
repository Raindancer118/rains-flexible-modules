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
 * The anti-inflation tools on a real server: nothing changes until the owner switches something on; then a hard
 * cap whose treasury pays out and fills up again, a payment tax in slices, a community fund, /repair, dying
 * costing money and /treasury.
 */
@Tag("e2e")
class EconomySupplyScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    /** Waits for a line, and says everything the bot was told when it never comes. */
    private static void expect(Bot bot, String what, String text) {
        try {
            Await.until(what, WAIT, () -> said(bot, text));
        } catch (AssertionError failed) {
            throw new AssertionError(failed.getMessage() + " — " + bot.name() + " saw " + bot.chatText(), failed);
        }
    }

    private static void set(Server server, String key, String value) {
        assertThat(server.console("settings set " + key + " " + value)).contains("is now");
        Await.ticks(40);
    }

    @Test
    @DisplayName("a capped treasury, taxes in slices, a fund, repair, dying and /treasury")
    void supply() {
        try (Server server = Server.start("economy-supply",
                List.of("economy-standalone:RainsEconomy-.*"), List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // ---- with the shipped settings nothing is capped
            ada.run("eco health");
            expect(ada, "the report says money is printed", "No cap");
            bo.run("treasury");
            expect(bo, "players cannot look by default", "not shown on this server");

            // ---- a cap of 2,500 with 2,000 out there: the treasury holds 500
            set(server, "money-supply.cap", "2500");
            set(server, "money-supply.capped", "true");
            ada.forgetChat();
            ada.run("eco health");
            expect(ada, "the report shows the cap", "Cap ⛃2,500");
            ada.forgetChat();
            ada.run("eco give Bo 600");
            expect(ada, "more than the treasury holds is refused", "treasury empty");
            ada.forgetChat();
            ada.run("eco give Bo 500");
            Await.until("exactly what it holds is paid", WAIT, () -> !said(ada, "treasury empty")
                    && ada.chatText().size() > 0);
            bo.forgetChat();
            bo.run("balance");
            expect(bo, "Bo has it", "You have ⛃1,500");
            ada.forgetChat();
            ada.run("eco give Bo 1");
            expect(ada, "now nothing more can be printed", "treasury empty");

            // ---- money taken goes back into the treasury
            ada.run("eco take Ada 100");
            Await.ticks(20);
            ada.forgetChat();
            ada.run("eco give Bo 100");
            Await.ticks(20);
            assertThat(said(ada, "treasury empty")).as("taking made room again").isFalse();
            ada.forgetChat();
            ada.run("eco health");
            expect(ada, "the report shows the cap", "Cap ⛃2,500");

            // ---- a payment taxed in slices: 10% on everything
            set(server, "pay.tax-by-brackets", "true");
            set(server, "pay.tax-brackets", "0 10");
            ada.forgetChat();
            ada.run("pay Bo 100");
            expect(ada, "the tax is named", "⛃10");

            // ---- bet insurance: offered once switched on; a lost flip pays part of the stake back
            ada.forgetChat();
            ada.run("casino insure");
            expect(ada, "not offered while switched off", "not offered");
            set(server, "gamble.insurance", "true");
            ada.forgetChat();
            ada.run("casino insure");
            expect(ada, "Ada's bets are insured", "Your bets are insured");
            boolean paidBack = false;
            for (int flip = 0; flip < 12 && !paidBack; flip++) {
                ada.forgetChat();
                ada.run("coinflip 10 heads");
                expect(ada, "the coin lands", "It landed");
                Await.ticks(10);
                paidBack = said(ada, "Bet insurance paid back");
                ada.closeWindow();
            }
            assertThat(paidBack).as("a lost flip was insured").isTrue();

            // ---- a community fund with a boost, filled by Bo
            set(server, "features.funds", "true");
            ada.forgetChat();
            ada.run("eco fund start Spawn 100 boost 50 1");
            expect(ada, "the fund starts", "collecting toward");
            bo.forgetChat();
            bo.run("fund");
            expect(bo, "Bo sees it", "Spawn");
            bo.run("fund Spawn 150");
            expect(ada, "everybody hears it is full", "fund is full");
            expect(bo, "Bo gave only what was missing", "You gave ⛃100");

            // ---- /repair offers a price for a worn item
            set(server, "features.repair", "true");
            server.console("give Ada minecraft:diamond_sword[minecraft:damage=800] 1");
            Await.until("the worn sword arrives", WAIT, () -> ada.carrying(item -> item.is("diamond_sword")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("diamond_sword")));
            ada.forgetChat();
            ada.run("repair");
            expect(ada, "a price is offered", "Repairing it costs");

            // ---- dying costs a tenth of the balance
            set(server, "death.costs-money", "true");
            set(server, "death.lose-percent", "10");
            bo.forgetChat();
            server.console("kill Bo");
            expect(bo, "Bo is told what dying cost", "Dying cost you");

            bo.respawn();
            Await.ticks(20);

            // ---- /treasury for everybody
            set(server, "features.treasury-info", "true");
            bo.forgetChat();
            bo.run("treasury");
            expect(bo, "Bo sees the money supply", "Money out there");
        }
    }
}
