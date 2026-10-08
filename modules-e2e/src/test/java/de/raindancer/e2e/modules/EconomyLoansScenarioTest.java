package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Loans, each game's own settings, spawn eggs in the shop and the owner's /eco page, on a real server. */
@Tag("e2e")
class EconomyLoansScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    /** Clicks the button named exactly this — "Shop" must not hit "Shop items". */
    private static void clickExact(Bot bot, String name) {
        int slot = Await.value(() -> bot.name() + " sees a button named exactly " + name, WAIT, () -> bot.window()
                .flatMap(window -> window.top().entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                        .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
        bot.clickSlot(slot);
    }

    private static boolean lore(Bot bot, String button, String text) {
        return bot.window().flatMap(window -> window.slotNamed(button).map(slot -> window.top().get(slot)))
                .map(item -> item.lore().stream().anyMatch(line -> line.contains(text))).orElse(false);
    }

    @Test
    @DisplayName("a loan taken, spent, paid back in part and forgiven; a game's own bet limit; eggs; /eco")
    void loans() {
        try (Server server = Server.start("economy-loans", List.of("economy-standalone:RainsEconomy-.*"),
                List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // ---- borrowing: the money arrives, the interest is added once, one loan at a time
            bo.run("loan take 2000");
            Await.until("Bo borrows", WAIT, () -> said(bo, "You borrowed ⛃2,000") && said(bo, "Pay back ⛃2,200"));
            bo.forgetChat();
            bo.run("balance");
            Await.until("Bo has 3,000", WAIT, () -> said(bo, "You have ⛃3,000"));
            bo.forgetChat();
            bo.run("loan take 100");
            Await.until("a second loan is refused", WAIT, () -> said(bo, "One loan at a time"));
            bo.forgetChat();
            bo.run("loan take 50000");
            Await.until("too much is refused", WAIT, () -> said(bo, "already owe"));

            // ---- the loan screen shows what is owed
            bo.run("loan");
            bo.awaitWindow("Loan");
            Await.until("the screen says what is owed", WAIT, () -> lore(bo, "Your loan", "⛃2,200"));
            bo.closeWindow();

            // ---- spawn eggs: a drawer of their own, the bosses' closed, a pig egg bought with the loan
            bo.run("shop");
            bo.awaitWindow("Shop");
            bo.click("Spawn eggs");
            bo.awaitWindow("Spawn eggs");
            assertThat(bo.window().orElseThrow().slotNamed("Allay Spawn Egg")).as("an allay's egg is for sale").isPresent();
            assertThat(bo.window().orElseThrow().slotNamed("Ender Dragon Spawn Egg")).as("a boss's is not").isEmpty();
            bo.closeWindow();
            bo.run("shop pig_spawn_egg");
            bo.awaitWindow("Pig Spawn Egg");
            Await.ticks(10);
            bo.forgetChat();
            // The harness now and then loses a click on a window it has only just been sent; a click that
            // bought nothing is simply made again, and never more than once an egg has arrived.
            for (int attempt = 0; attempt < 3 && !said(bo, "You bought"); attempt++) {
                clickExact(bo, "Buy 1");
                Await.ticks(40);
            }
            Await.until(() -> "the egg is bought (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "You bought 1 × Pig Spawn Egg"));
            bo.closeWindow();

            // ---- a shop egg never sets a spawner: refused on one, the spawner stays empty; it still sells back
            Await.until("the egg carries the mark", WAIT, () -> bo.carrying(item -> item.is("pig_spawn_egg")
                    && item.tag("not-for-spawners").isPresent()).isPresent());
            int x = (int) Math.floor(bo.position().getX()) + 2;
            int y = (int) Math.floor(bo.position().getY());
            int z = (int) Math.floor(bo.position().getZ());
            server.console("setblock " + x + " " + y + " " + z + " minecraft:spawner");
            Await.ticks(10);
            bo.hold(bo.hotbarSlotOf(item -> item.is("pig_spawn_egg")));
            bo.forgetChat();
            bo.useOn(x, y, z);
            Await.until(() -> "the spawner refuses it (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "don't set spawners"));
            assertThat(server.console("data get block " + x + " " + y + " " + z + " SpawnData"))
                    .as("the spawner holds no pig").doesNotContain("pig");
            assertThat(bo.carrying(item -> item.is("pig_spawn_egg"))).as("and the egg is not used up").isPresent();
            bo.forgetChat();
            bo.run("sell hand");
            Await.until(() -> "the shop's egg sells back (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "You sold"));
            bo.closeWindow();

            // ---- paying back what he has: 1,800 of 2,200 (1,000 left after the egg, 800 for selling it back)
            bo.forgetChat();
            bo.run("loan repay");
            Await.until("part is paid back", WAIT, () -> said(bo, "You paid back ⛃1,800") && said(bo, "⛃400"));

            // ---- staff see it and forgive the rest
            ada.forgetChat();
            ada.run("eco loan Bo");
            Await.until("Ada sees the loan", WAIT, () -> said(ada, "owes ⛃400"));
            bo.forgetChat();
            ada.run("eco loan Bo forgive");
            Await.until("the loan is forgiven", WAIT, () -> said(ada, "is forgiven") && said(bo, "forgave your loan"));
            bo.forgetChat();
            bo.run("loan repay");
            Await.until("nothing is owed now", WAIT, () -> said(bo, "You owe the bank nothing"));

            // ---- a game's own largest bet wins over the casino's (which has none)
            server.console("settings set economy:dice.max-bet 5");
            server.console("settings set economy:slots.house-edge-percent 8");
            Await.ticks(10);
            ada.run("eco give Bo 500");
            Await.ticks(10);
            bo.forgetChat();
            bo.run("dice 50 over 50");
            // The bet is held to the table's limit: 50 becomes 5, so the balance moves by 5 or a win on 5.
            Await.until(() -> "the dice table's own limit (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "Balance: ⛃495") || said(bo, "Balance: ⛃504"));

            // ---- /eco: the casino page shows each game's own edge; a door opens a settings page at its topic
            ada.run("eco");
            ada.awaitWindow("Run the econom");
            clickExact(ada, "Casino");
            ada.awaitWindow("Casino");
            Await.until("slots keep their own edge", WAIT, () -> lore(ada, "Slot machine", "8.0%"));
            Await.until("dice keep the casino's", WAIT, () -> lore(ada, "Dice", "3.0%"));
            ada.click("Slot machine");
            ada.awaitWindow("Slot machine");
            assertThat(ada.window().orElseThrow().slotNamed("The house keeps, percent")).isPresent();
            ada.closeWindow();
            ada.run("eco");
            ada.awaitWindow("Run the econom");
            ada.click("Interest");
            ada.awaitWindow("Interest");
            assertThat(ada.window().orElseThrow().slotNamed("Percent per payout")).isPresent();
            ada.closeWindow();

            // ---- a long settings page is paged rather than cut off: the shop's last settings can be reached
            ada.run("eco");
            ada.awaitWindow("Run the econom");
            clickExact(ada, "Shop");
            ada.awaitWindow("Shop");
            Await.until("the shop's settings have a second page", WAIT,
                    () -> ada.window().flatMap(window -> window.slotNamed("Next page")).isPresent());
            ada.click("Next page");
            Await.until("and on it the last of them", WAIT,
                    () -> ada.window().flatMap(window -> window.slotNamed("Each enchantment level is worth")).isPresent());
            ada.closeWindow();

            // ---- netherite gear is in the shop: the upgrade template has a price, so smithing prices the rest
            bo.run("shop netherite_sword");
            bo.awaitWindow("Netherite Sword");
            assertThat(bo.window().orElseThrow().slotNamed("Buy 1")).as("a netherite sword is for sale").isPresent();
            bo.closeWindow();

            // ---- auctioning straight from the inventory: click the stack below, set it up, put it up
            ada.run("eco give Bo 5000");
            server.console("give Bo minecraft:diamond 3");
            Await.ticks(10);
            bo.run("auction");
            bo.awaitWindow("Auction house");
            Await.ticks(10);
            assertThat(bo.window().orElseThrow().slotNamed("Put something up")).as("the button is shown").isPresent();
            int diamonds = Await.value(() -> "Bo sees his diamonds below the auction house", WAIT, () -> bo.window()
                    .flatMap(window -> window.items().entrySet().stream()
                            .filter(entry -> entry.getKey() >= window.size() && entry.getValue().is("diamond"))
                            .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
            bo.clickSlot(diamonds);
            bo.awaitWindow("Auction this");
            Await.ticks(10);
            bo.forgetChat();
            bo.click("Put it up");
            Await.until(() -> "the diamonds go up (Bo heard " + bo.chatText() + ")", WAIT,
                    () -> bo.carrying(item -> item.is("diamond")).isEmpty());

            // ---- a dealer wears the suit: its profile carries the shipped, signed suit texture
            ada.run("eco dealer place blackjack");
            Await.ticks(10);
            String profile = server.console("data get entity @e[type=minecraft:mannequin,limit=1] profile");
            assertThat(profile).as("the dealer's profile").contains("ewogICJ0aW1lc3RhbXAiIDogMTcwMTE5MTAzMjkx");

            assertThat(server.paper.logLines(line -> line.contains("Exception"))).as("nothing threw").isEmpty();
        }
    }
}
