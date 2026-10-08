package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The economy on a real server: accounts, paying, cash out and back in, a duplicated banknote caught, the
 * shop's menus, every game of chance with its animation, the daily reward, the lottery and the staff tools.
 */
@Tag("e2e")
class EconomyScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static Predicate<Bot.Item> cash() {
        return item -> item.tag("rainseconomy:value").isPresent();
    }

    private static int cashPieces(Bot bot) {
        return bot.items().stream().filter(cash()).mapToInt(Bot.Item::amount).sum();
    }

    @Test
    @DisplayName("accounts, paying, cash, a caught duplicate, the shop, the casino and the staff tools")
    void economy() {
        try (Server server = Server.start("economy",
                List.of("economy-standalone:RainsEconomy-.*"), List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // ---- an account for everybody who joins, with the starting balance
            ada.run("balance");
            Await.until("Ada sees her balance", WAIT, () -> said(ada, "You have ⛃100"));

            // ---- paying
            ada.forgetChat();
            ada.run("pay Bo 25");
            Await.until("Ada is told", WAIT, () -> said(ada, "You paid ⛃25"));
            Await.until("Bo is told", WAIT, () -> said(bo, "Ada paid you ⛃25"));
            bo.forgetChat();
            bo.run("balance");
            Await.until("Bo has 125", WAIT, () -> said(bo, "You have ⛃125"));
            ada.forgetChat();
            ada.run("pay Ada 5");
            Await.until("nobody pays themselves", WAIT, () -> said(ada, "cannot pay yourself"));

            // ---- cash out: one 50 note and two 10 coins for 70
            ada.forgetChat();
            ada.run("withdraw 70");
            Await.until("the cash arrives", WAIT, () -> cashPieces(ada) == 3);
            assertThat(ada.carrying(item -> item.is("paper") && item.tag("rainseconomy:serial").isPresent()))
                    .as("the note is numbered").isPresent();
            assertThat(ada.carrying(item -> item.is("gold_ingot") && item.amount() == 2)).isPresent();
            String serial = ada.carrying(item -> item.is("paper")).flatMap(item -> item.tag("rainseconomy:serial"))
                    .orElseThrow();

            // ---- and back in, all of it
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("the cash is gone", WAIT, () -> cashPieces(ada) == 0);
            Await.until("Ada is told", WAIT, () -> said(ada, "You paid in ⛃70"));

            // ---- a copy of that note, made the way a duplication glitch would, is caught
            server.console("give Ada minecraft:paper[minecraft:custom_data={PublicBukkitValues:{"
                    + "\"rainseconomy:value\":5000L,\"rainseconomy:form\":\"NOTE\",\"rainseconomy:serial\":\""
                    + serial + "\"}}]");
            Await.until("the copy arrives", WAIT, () -> cashPieces(ada) == 1);
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("the copy is confiscated", WAIT, () -> cashPieces(ada) == 0);
            Await.until("and called what it is", WAIT, () -> said(ada, "already been paid in"));
            ada.forgetChat();
            ada.run("balance");
            Await.until("nothing was credited for it", WAIT, () -> said(ada, "You have ⛃75"));

            // ---- a coin claiming to be worth a million — what a hacked creative client could send — is not
            server.console("give Ada minecraft:gold_ingot[minecraft:custom_data={PublicBukkitValues:{"
                    + "\"rainseconomy:value\":100000000L,\"rainseconomy:form\":\"COIN\"}}] 3");
            Await.until("the forged coins arrive", WAIT, () -> cashPieces(ada) == 3);
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("they are confiscated", WAIT, () -> cashPieces(ada) == 0);
            Await.until("as forgeries", WAIT, () -> said(ada, "not this server's money"));
            ada.forgetChat();
            ada.run("balance");
            Await.until("and are worth nothing", WAIT, () -> said(ada, "You have ⛃75"));

            // ---- the shop: drawers like the creative inventory, then one item
            ada.run("shop");
            ada.awaitWindow("Shop");
            ada.click("Building Blocks");
            ada.awaitWindow("Building Blocks");
            assertThat(ada.window().orElseThrow().slotNamed("Acacia Log")).as("logs are for sale").isPresent();
            ada.closeWindow();
            ada.run("shop oak_log");
            ada.awaitWindow("Oak Log");
            ada.forgetChat();
            ada.click("Buy 8");
            Await.until("eight logs arrive", WAIT,
                    () -> ada.carrying(item -> item.is("oak_log") && item.amount() == 8).isPresent());
            Await.until("Ada is told", WAIT, () -> said(ada, "You bought 8 × Oak Log"));
            ada.click("Sell 8");
            Await.until("the logs are sold", WAIT, () -> ada.carrying(item -> item.is("oak_log")).isEmpty());
            Await.until("Ada is told", WAIT, () -> said(ada, "You sold"));
            ada.closeWindow();

            // ---- a coin flip: the coin spins in the window, then lands
            ada.forgetChat();
            ada.run("coinflip 1 heads");
            ada.awaitWindow("Coin flip");
            Await.until("the coin is spinning", WAIT,
                    () -> ada.window().flatMap(window -> window.slotNamed("…")).isPresent());
            Await.until("it lands and Ada is told", WAIT, () -> said(ada, "It landed"));
            Await.until("the window shows the side", WAIT, () -> ada.window()
                    .flatMap(window -> window.slotNamed("Heads!").or(() -> window.slotNamed("Tails!"))).isPresent());
            ada.closeWindow();

            // ---- dice: the marker runs, the roll is told
            ada.forgetChat();
            ada.run("dice 1 over 50");
            ada.awaitWindow("Dice");
            Await.until("the dice are rolled", WAIT, () -> said(ada, "You rolled"));
            ada.closeWindow();

            // ---- slots: the reels spin and stop
            ada.forgetChat();
            ada.run("slots");
            ada.awaitWindow("Slot machine");
            ada.click("Spin");
            Await.until("the reels stop", WAIT, () -> said(ada, "you won") || said(ada, "you lost"));
            ada.closeWindow();

            // ---- closing the window mid-spin still settles and tells the result
            ada.forgetChat();
            ada.run("coinflip 1 tails");
            ada.awaitWindow("Coin flip");
            ada.closeWindow();
            Await.until("the result is told all the same", WAIT, () -> said(ada, "It landed"));

            // ---- the daily reward, once
            bo.forgetChat();
            bo.run("daily");
            Await.until("Bo gets it", WAIT, () -> said(bo, "Daily reward: ⛃50"));
            bo.run("daily");
            Await.until("but not twice", WAIT, () -> said(bo, "You have had today's reward"));

            // ---- the lottery
            bo.forgetChat();
            bo.run("lottery buy 2");
            Await.until("Bo holds tickets", WAIT, () -> said(bo, "You bought 2 ticket(s)"));
            ada.forgetChat();
            ada.run("eco draw");
            Await.until("everybody hears who won", WAIT, () -> said(ada, "Lottery: Bo won"));

            // ---- staff
            bo.forgetChat();
            bo.run("eco give Bo 1000");
            Await.until("Bo may not", WAIT, () -> said(bo, "You may not do that"));
            ada.forgetChat();
            ada.run("eco set Bo 500");
            Await.until("Ada sets it", WAIT, () -> said(ada, "now has ⛃500"));
            ada.run("eco freeze Bo");
            Await.until("the account is frozen", WAIT, () -> said(ada, "account is frozen"));
            bo.forgetChat();
            bo.run("pay Ada 1");
            Await.until("a frozen account cannot pay", WAIT, () -> said(bo, "frozen"));

            // ---- the bank window opens with every door in it
            ada.run("bank");
            ada.awaitWindow("Bank");
            assertThat(ada.window().orElseThrow().slotNamed("Casino")).isPresent();
            assertThat(ada.window().orElseThrow().slotNamed("Withdraw cash")).isPresent();
            ada.closeWindow();

            // ---- a coin is money, not gold: a crafting table will not take it, but takes plain gold
            ada.run("withdraw 10");
            Await.until("a gold coin arrives", WAIT, () -> ada.carrying(cash()).isPresent());
            server.console("clear Ada minecraft:paper");
            server.console("give Ada minecraft:gold_ingot 1");
            Await.until("and a plain ingot", WAIT, () -> ada.carrying(item -> item.is("gold_ingot")
                    && item.tag("rainseconomy:value").isEmpty()).isPresent());
            var feet = ada.position();
            int x = (int) Math.floor(feet.getX()) + 1;
            int y = (int) Math.floor(feet.getY());
            int z = (int) Math.floor(feet.getZ());
            server.console("setblock " + x + " " + y + " " + z + " minecraft:crafting_table");
            int coin = ada.hotbarSlotOf(item -> item.tag("rainseconomy:value").isPresent());
            int plain = ada.hotbarSlotOf(item -> item.is("gold_ingot") && item.tag("rainseconomy:value").isEmpty());
            // An empty hand: right clicking with cash in it pays the cash in instead of opening the table.
            ada.hold(8);
            Await.ticks(5);
            ada.useOn(x, y, z);
            ada.awaitWindow("crafting");
            assertThat(coin).as("the coin is in the hotbar").isNotNegative();
            assertThat(plain).as("the plain ingot is in the hotbar").isNotNegative();
            ada.clickSlot(37 + coin);
            Await.ticks(5);
            ada.clickSlot(1);
            // Asked of the server, not the bot's picture of the grid: the click that would put the coin in
            // was cancelled there.
            Await.until("the server refused the coin", WAIT, () -> server.paper.eventsText().lines()
                    .anyMatch(line -> line.contains("\"title\":\"Crafting\",\"slot\":1,") && line.contains("\"cancelled\":true")));
            ada.closeWindow();
            Await.ticks(10);
            assertThat(ada.carrying(cash())).as("the coin is still Ada's").isPresent();
            ada.useOn(x, y, z);
            ada.awaitWindow("crafting");
            Await.ticks(5);
            ada.clickSlot(37 + plain);
            Await.ticks(5);
            ada.clickSlot(1);
            Await.until("plain gold goes into the grid", WAIT, () -> server.paper.eventsText().lines()
                    .anyMatch(line -> line.contains("\"title\":\"Crafting\",\"slot\":1,") && line.contains("\"cancelled\":false")));
            ada.closeWindow();

            assertThat(server.paper.logLines(line -> line.contains("Exception"))).as("nothing threw").isEmpty();
        }
    }
}
