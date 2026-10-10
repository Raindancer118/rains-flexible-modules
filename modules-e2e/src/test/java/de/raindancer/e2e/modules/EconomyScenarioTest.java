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

    private static void clickNamed(Bot bot, String name) {
        int slot = Await.value(() -> bot.name() + " sees a button named exactly " + name, WAIT, () -> bot.window()
                .flatMap(window -> window.top().entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                        .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
        bot.clickSlot(slot);
    }

    /** What the window shows, written beside the server's logs for a look at it later. */
    private static void snapshot(Bot bot, String name) {
        bot.window().ifPresent(window -> {
            StringBuilder out = new StringBuilder(window.title()).append('\n');
            window.top().forEach((slot, item) -> out.append(slot).append('\t').append(item.material()).append('\t')
                    .append(item.amount()).append('\t').append(item.name()).append('\t')
                    .append(String.join(" | ", item.lore())).append('\n'));
            try {
                java.nio.file.Path file = java.nio.file.Path.of("target", "e2e", "screens", name + ".tsv");
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, out);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static Predicate<Bot.Item> cash() {
        return item -> item.tag("rainseconomy:value").isPresent();
    }

    private static int cashPieces(Bot bot) {
        return bot.items().stream().filter(cash()).mapToInt(Bot.Item::amount).sum();
    }

    /** What the last sale fetched, read off the line that said so. */
    private static long soldFor(Bot bot) {
        String line = bot.chatText().stream().filter(text -> text.contains("You sold")).reduce((a, b) -> b).orElseThrow();
        String amount = line.substring(line.lastIndexOf('⛃') + 1).replaceAll("[^0-9]", "");
        return Long.parseLong(amount);
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
            Await.until("Ada sees her balance", WAIT, () -> said(ada, "You have ⛃1,000"));

            // ---- paying
            ada.forgetChat();
            ada.run("pay Bo 25");
            Await.until("Ada is told", WAIT, () -> said(ada, "You paid ⛃25"));
            Await.until("Bo is told", WAIT, () -> said(bo, "Ada paid you ⛃25"));
            bo.forgetChat();
            bo.run("balance");
            Await.until("Bo has 1,025", WAIT, () -> said(bo, "You have ⛃1,025"));
            ada.forgetChat();
            ada.run("pay Ada 5");
            Await.until("nobody pays themselves", WAIT, () -> said(ada, "cannot pay yourself"));

            // ---- cash out: seventy coins, one kind of coin worth one
            ada.forgetChat();
            ada.run("withdraw 70");
            Await.until("the coins arrive", WAIT, () -> cashPieces(ada) == 70);
            assertThat(ada.items().stream().filter(cash()).allMatch(item -> item.is("gold_nugget")
                    && item.tag("rainseconomy:seal").isPresent())).as("sealed gold coins, nothing else").isTrue();

            // ---- and back in, all of it
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("the cash is gone", WAIT, () -> cashPieces(ada) == 0);
            Await.until("Ada is told", WAIT, () -> said(ada, "You paid in ⛃70"));

            // ---- a cheque, paid in; then a perfect copy of it — seal and all, as a duplication glitch makes — is caught
            ada.run("withdraw 50 cheque");
            Await.until("the cheque arrives", WAIT, () -> ada.carrying(item -> item.is("paper")).isPresent());
            Bot.Item cheque = ada.carrying(item -> item.is("paper")).orElseThrow();
            String serial = cheque.tag("rainseconomy:serial").orElseThrow();
            String seal = cheque.tag("rainseconomy:seal").orElseThrow();
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("the cheque is paid in", WAIT, () -> said(ada, "You paid in ⛃50"));
            server.console("give Ada minecraft:paper[minecraft:custom_data={PublicBukkitValues:{"
                    + "\"rainseconomy:value\":50L,\"rainseconomy:form\":\"NOTE\",\"rainseconomy:cheque\":1b,"
                    + "\"rainseconomy:serial\":\"" + serial + "\",\"rainseconomy:seal\":\"" + seal + "\"}}]");
            Await.until("the copy arrives", WAIT, () -> cashPieces(ada) == 1);
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("the copy is confiscated", WAIT, () -> cashPieces(ada) == 0);
            Await.until("and called what it is", WAIT, () -> said(ada, "already been paid in"));
            ada.forgetChat();
            ada.run("balance");
            Await.until("nothing was credited for it", WAIT, () -> said(ada, "You have ⛃975"));

            // ---- a coin without the server's seal — what a hacked creative client could send — is worth nothing
            server.console("give Ada minecraft:gold_nugget[minecraft:custom_data={PublicBukkitValues:{"
                    + "\"rainseconomy:value\":1L,\"rainseconomy:form\":\"COIN\"}}] 30");
            Await.until("the forged coins arrive", WAIT, () -> cashPieces(ada) == 30);
            ada.forgetChat();
            ada.run("deposit all");
            Await.until("they are confiscated", WAIT, () -> cashPieces(ada) == 0);
            Await.until("as forgeries", WAIT, () -> said(ada, "not this server's money"));
            ada.forgetChat();
            ada.run("balance");
            Await.until("and are worth nothing", WAIT, () -> said(ada, "You have ⛃975"));

            // ---- an enchanted sword sells for more than a plain one
            server.console("give Ada minecraft:diamond_sword 1");
            Await.until("the plain sword arrives", WAIT, () -> ada.carrying(item -> item.is("diamond_sword")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("diamond_sword")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            Await.until("the plain sword is sold", WAIT, () -> said(ada, "You sold 1 × Diamond Sword for"));
            long plainSword = soldFor(ada);
            server.console("give Ada minecraft:diamond_sword[minecraft:enchantments={\"minecraft:sharpness\":5}] 1");
            Await.until("the enchanted sword arrives", WAIT, () -> ada.carrying(item -> item.is("diamond_sword")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("diamond_sword")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            Await.until("the enchanted sword is sold", WAIT, () -> said(ada, "Diamond Sword (enchanted) for"));
            long sharpSword = soldFor(ada);
            assertThat(sharpSword).as("worth more enchanted").isGreaterThan(plainSword);
            // The same five levels of something far less useful are worth far less.
            server.console("give Ada minecraft:diamond_sword[minecraft:enchantments={\"minecraft:bane_of_arthropods\":5}] 1");
            Await.until("the bane sword arrives", WAIT, () -> ada.carrying(item -> item.is("diamond_sword")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("diamond_sword")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            Await.until("the bane sword is sold", WAIT, () -> said(ada, "Diamond Sword (enchanted) for"));
            long baneSword = soldFor(ada);
            assertThat(baneSword).as("an enchantment adds what it is useful for").isGreaterThan(plainSword).isLessThan(sharpSword);

            // ---- the enchantments sold off, the sword kept — and its curse with it
            server.console("give Ada minecraft:diamond_sword[minecraft:enchantments={\"minecraft:sharpness\":5,"
                    + "\"minecraft:vanishing_curse\":1}] 1");
            Await.until("the sword to strip arrives", WAIT, () -> ada.carrying(item -> item.is("diamond_sword")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("diamond_sword")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell enchantments");
            Await.until("the enchantments are sold", WAIT, () -> said(ada, "sold the enchantments off your Diamond Sword for"));
            assertThat(ada.carrying(item -> item.is("diamond_sword"))).as("the sword is kept").isPresent();
            ada.forgetChat();
            ada.run("sell enchantments");
            Await.until("a curse is not for sale", WAIT, () -> said(ada, "Curses cannot be sold off"));
            server.console("clear Ada minecraft:diamond_sword");
            server.console("give Ada minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:mending\":1}] 1");
            Await.until("the book arrives", WAIT, () -> ada.carrying(item -> item.is("enchanted_book")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("enchanted_book")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell enchantments");
            Await.until("Mending is sold", WAIT, () -> said(ada, "sold the enchantments off your Enchanted Book for"));
            Await.until("a plain book is left", WAIT, () -> ada.carrying(item -> item.is("book")).isPresent());
            // ---- and an enchanted book sells whole, for more than a plain one
            server.console("give Ada minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:protection\":4}] 1");
            Await.until("the protection book arrives", WAIT, () -> ada.carrying(item -> item.is("enchanted_book")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("enchanted_book")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            Await.until("the enchanted book is sold", WAIT, () -> said(ada, "Enchanted Book (enchanted) for"));

            // ---- the statement, printed as a book to keep
            ada.run("bank");
            ada.awaitWindow("Bank");
            ada.shiftClickSlot(ada.window().orElseThrow().slotNamed("Statement").orElseThrow());
            Await.until("a printed statement arrives", WAIT,
                    () -> ada.carrying(item -> item.is("written_book")).isPresent());
            ada.closeWindow();

            // ---- hiring: an offer, accepted from chat, and the job on both pages
            bo.forgetChat();
            ada.run("hire Bo 5 1h miner");
            Await.until("Bo is offered the job", WAIT, () -> said(bo, "wants to hire you as miner"));
            bo.clickButtonOn("wants to hire you", 0);
            Await.until("Ada is told", WAIT, () -> said(ada, "Bo took the job"));
            ada.run("hire");
            ada.awaitWindow("Jobs");
            assertThat(ada.window().orElseThrow().slotNamed("You employ Bo")).isPresent();
            ada.closeWindow();

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

            // ---- roulette: the wheel turns, slows, and the ball lands
            ada.forgetChat();
            ada.run("roulette 1");
            ada.awaitWindow("Roulette");
            ada.click("Spin");
            Await.until("the ball lands", Duration.ofSeconds(30), () -> said(ada, "The ball lands on"));
            ada.closeWindow();
            // And it was heard: the coin spinning, the wheel ticking, and a win or a loss.
            assertThat(ada.soundsHeard()).anyMatch(sound -> sound.contains("experience_orb"));
            assertThat(ada.soundsHeard()).anyMatch(sound -> sound.contains("note_block.hat"));
            assertThat(ada.soundsHeard()).anyMatch(sound -> sound.contains("note_block.bell")
                    || sound.contains("note_block.bass") || sound.contains("challenge_complete"));

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
            Await.until("Bo gets it", WAIT, () -> said(bo, "Daily reward: ⛃500"));
            bo.run("daily");
            Await.until("but not twice", WAIT, () -> said(bo, "You have had today's reward"));

            // ---- the lottery
            bo.forgetChat();
            bo.run("lottery buy 2");
            Await.until("Bo holds quick-picked tickets", WAIT, () -> said(bo, "You bought 2 ticket(s)"));

            // ---- staff
            bo.forgetChat();
            bo.run("eco give Bo 1000");
            Await.until("Bo may not", WAIT, () -> said(bo, "You may not do that"));
            ada.forgetChat();
            ada.run("eco set Bo 0");
            Await.until("Ada empties it", WAIT, () -> said(ada, "now has ⛃0"));
            ada.run("eco set Bo -5");
            Await.until("a negative balance is refused", WAIT, () -> said(ada, "-5 is not an amount"));
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

            // ---- blackjack: dealt card by card, stood, settled by the dealer
            server.console("settings set economy:gamble.cooldown-seconds 0");
            ada.forgetChat();
            ada.run("blackjack");
            ada.awaitWindow("Blackjack");
            // "Deal" is also part of the dealer's name in the header.
            clickNamed(ada, "Deal");
            Await.ticks(60);
            snapshot(ada, "blackjack-hand");
            ada.window().flatMap(window -> window.slotNamed("Stand")).ifPresent(ada::clickSlot);
            Await.ticks(60);
            snapshot(ada, "blackjack-settled");
            Await.until("the dealer settles it", Duration.ofSeconds(20), () -> said(ada, "Dealer:")
                    || said(ada, "A push"));
            ada.closeWindow();

            // ---- baccarat: a bet on the player, the coup laid out by the rules
            ada.forgetChat();
            ada.run("baccarat");
            ada.awaitWindow("Baccarat");
            ada.click("Player");
            Await.until("the coup is settled", Duration.ofSeconds(20), () -> said(ada, "You win") || said(ada, "You lose")
                    || said(ada, "comes back"));
            ada.closeWindow();

            // ---- hi-lo and mines: started, then the window closed — which cashes out
            ada.forgetChat();
            ada.run("hilo");
            ada.awaitWindow("Hi-Lo");
            ada.click("Start");
            Await.until("the first card shows", WAIT, () -> ada.window().flatMap(window -> window.slotNamed("Higher")
                    .or(() -> window.slotNamed("Lower"))).isPresent());
            ada.closeWindow();
            Await.until("the stake comes back", WAIT, () -> said(ada, "comes back"));
            ada.forgetChat();
            ada.run("mines");
            ada.awaitWindow("Mines");
            ada.click("Start");
            Await.until("the field is ready", WAIT, () -> ada.window().flatMap(window -> window.slotNamed("Cash out")).isPresent());
            ada.clickSlot(ada.window().orElseThrow().slotNamed("?").orElseThrow());
            Await.ticks(10);
            ada.closeWindow();
            Await.until("cashed out or blown up", WAIT, () -> said(ada, "tiles cleared") || said(ada, "Boom"));

            // ---- crash: join, cash out by itself at 1.5×, or crash before
            server.console("settings set economy:crash.betting-seconds 10");
            ada.forgetChat();
            ada.run("crash");
            ada.awaitWindow("Crash");
            Await.until("bets are open", Duration.ofSeconds(150), () -> ada.window()
                    .flatMap(window -> window.slotNamed("Join the round")).isPresent());
            ada.click("Cash out by itself");
            Await.ticks(5);
            ada.click("Join the round");
            Await.until("the round ends for Ada", Duration.ofSeconds(40), () -> said(ada, "Cashed out at")
                    || said(ada, "Crashed at"));
            ada.closeWindow();

            // ---- the horse race: a bet on Thunder, then the race
            server.console("settings set economy:race.betting-seconds 10");
            ada.forgetChat();
            ada.run("race");
            ada.awaitWindow("Horse race");
            Await.until("the gate is closed", Duration.ofSeconds(40), () -> ada.window()
                    .flatMap(window -> window.slotNamed("Gate opens")).isPresent());
            ada.click("Thunder");
            Await.until("the race is run", Duration.ofSeconds(60), () -> said(ada, "wins"));
            ada.closeWindow();

            // ---- a scratch card, bought, scratched
            ada.forgetChat();
            ada.run("scratch buy 1");
            Await.until("the ticket arrives", WAIT, () -> ada.carrying(item -> item.is("map")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("map")));
            Await.ticks(5);
            ada.useHeld();
            ada.awaitWindow("Scratch card");
            ada.click("Scratch everything");
            Await.until("the card is told", WAIT, () -> said(ada, "Three alike") || said(ada, "No three alike"));
            ada.closeWindow();

            // ---- the lottery: Ada's own numbers, then the draw called ball by ball
            ada.forgetChat();
            ada.run("lottery buy 1 2 3 4");
            Await.until("the ticket is bought", WAIT, () -> said(ada, "You bought 1 ticket(s) (1 2 3 4)"));
            ada.forgetChat();
            ada.run("eco draw");
            Await.until("the balls are called", Duration.ofSeconds(30), () -> said(ada, "Ball 4 of 4"));
            Await.until("and the result told", Duration.ofSeconds(30), () -> said(ada, "rolls over") || said(ada, "wins"));

            // ---- a dealer, placed in the casino
            ada.forgetChat();
            ada.run("eco dealer place blackjack");
            Await.until("the dealer stands there", WAIT, () -> server.console(
                    "execute if entity @e[type=minecraft:mannequin]").contains("passed"));

            // ---- any item can be the coin; coins already out keep working
            server.console("item replace entity Ada weapon.mainhand with minecraft:emerald");
            Await.ticks(5);
            ada.forgetChat();
            ada.run("eco coin");
            Await.until("coins are emeralds now", WAIT, () -> said(ada, "Coins are now made of emerald"));
            ada.run("withdraw 3");
            Await.until("three emerald coins", WAIT, () -> ada.items().stream()
                    .anyMatch(item -> item.is("emerald") && item.tag("rainseconomy:value").isPresent() && item.amount() == 3));

            // ---- a coin of ender pearls still stacks to 64
            server.console("item replace entity Ada weapon.mainhand with minecraft:ender_pearl");
            Await.ticks(5);
            ada.run("eco coin");
            Await.until("coins are ender pearls", WAIT, () -> said(ada, "Coins are now made of ender pearl"));
            ada.run("withdraw 64");
            Await.until("one stack of sixty-four pearl coins", WAIT, () -> ada.items().stream()
                    .anyMatch(item -> item.is("ender_pearl") && item.amount() == 64));

            // ---- a leaderboard of the richest players, put down in the world
            ada.forgetChat();
            ada.run("eco leaderboard place");
            Await.until("it is placed", WAIT, () -> said(ada, "leaderboard of the richest players floats here"));
            Await.until("and a display stands there", WAIT, () -> server.console(
                    "execute if entity @e[type=minecraft:text_display]").contains("passed"));

            // ---- an auction: announced to everybody, bid on, outbid and paid back, sold under the hammer
            ada.run("eco unfreeze Bo");
            ada.run("eco set Bo 5000");
            Bot cy = server.player("Cy");
            server.console("settings set economy:auction.gap-seconds 0");
            server.console("settings set economy:auction.snipe-seconds 5");
            server.console("item replace entity Bo weapon.mainhand with minecraft:diamond 3");
            Await.ticks(10);
            ada.forgetChat();
            bo.forgetChat();
            cy.forgetChat();
            bo.run("auction sell 50 30s");
            Await.until("everybody hears of it", Duration.ofSeconds(20), () -> said(ada, "puts up") && said(cy, "puts up"));
            Await.until("listing cost Bo the fee", WAIT, () -> said(bo, "Listing fee: ⛃1,000"));
            assertThat(bo.carrying(item -> item.is("diamond"))).as("the auction house holds the diamonds").isEmpty();
            Await.until("the auction is on a boss bar", WAIT, () -> ada.bossBars().stream()
                    .anyMatch(bar -> bar.contains("Auction")));
            bo.run("auction bid 60");
            Await.until("nobody bids on their own", WAIT, () -> said(bo, "cannot bid on your own"));
            ada.run("auction bid");
            Await.until("Ada bids the starting price", WAIT, () -> said(cy, "Ada bids ⛃50"));
            cy.run("auction bid 55");
            Await.until("too small a step is refused", WAIT, () -> said(cy, "at least ⛃60"));
            cy.run("auction bid 100");
            Await.until("Ada is outbid and paid back", WAIT, () -> said(ada, "You were outbid")
                    && said(ada, "Your ⛃50 is back"));
            ada.run("auction");
            ada.awaitWindow("Auction house");
            assertThat(ada.window().orElseThrow().top().values()).as("the item, live in the window")
                    .anyMatch(item -> item.is("diamond") && item.amount() == 3);
            ada.closeWindow();
            Await.until("sold", Duration.ofSeconds(60), () -> said(ada, "SOLD!") && said(ada, "Cy wins"));
            assertThat(ada.chatText()).as("the countdown is on the boss bar only, not in chat")
                    .noneMatch(line -> line.contains("s left"));
            Await.until("Cy has the diamonds", WAIT, () -> cy.carrying(item -> item.is("diamond") && item.amount() == 3)
                    .isPresent());
            Await.until("Bo is paid, less the fee", WAIT, () -> said(bo, "sold for ⛃100") && said(bo, "⛃95"));
            Await.until("the bar is gone", WAIT, () -> ada.bossBars().stream().noneMatch(bar -> bar.contains("Auction")));

            // ---- staff call auctions off: a queued one by its seller's name, then the running one, bid and all
            server.console("item replace entity Bo weapon.mainhand with minecraft:emerald 2");
            Await.ticks(10);
            bo.run("auction sell 50 5m");
            Await.until("the first is live", WAIT, () -> ada.bossBars().stream().anyMatch(bar -> bar.contains("Auction")));
            server.console("item replace entity Bo weapon.mainhand with minecraft:gold_ingot 2");
            Await.ticks(10);
            bo.run("auction sell 50 5m");
            Await.until("the second waits in the queue", WAIT, () -> server.console("auction info").contains("waiting"));
            cy.forgetChat();
            cy.run("auction bid");
            Await.until("Cy bids on the live one", WAIT, () -> said(cy, "Cy bids"));
            assertThat(server.console("eco auction cancel Bo")).as("Bo's newest: the queued gold").contains("Called off")
                    .contains("Gold Ingot");
            Await.until("the gold is back with Bo", WAIT, () -> bo.carrying(item -> item.is("gold_ingot")).isPresent());
            assertThat(server.console("eco auction cancel Bo")).as("then the live one, though it has a bid")
                    .contains("Called off");
            Await.until("the emeralds are back with Bo", WAIT, () -> bo.carrying(item -> item.is("emerald")).isPresent());
            Await.until("Cy is told", WAIT, () -> said(cy, "called off"));
            assertThat(server.console("eco auction cancel Nobody")).contains("No auction fits");

            // ---- a raffle: started from the hand, tickets bought by command and by chat button, drawn
            server.console("settings set economy:raffle.min-minutes 1");
            server.console("item replace entity Bo weapon.mainhand with minecraft:golden_apple 2");
            Await.ticks(10);
            ada.forgetChat();
            bo.forgetChat();
            cy.forgetChat();
            bo.run("raffle start 10 1m");
            Await.until("everybody hears of it", WAIT, () -> said(ada, "raffles off") && said(cy, "raffles off"));
            Await.until("starting it was free", WAIT, () -> said(bo, "Fee: ⛃0"));
            assertThat(bo.carrying(item -> item.is("golden_apple"))).as("the raffle holds the apples").isEmpty();
            bo.run("raffle buy 1 1");
            Await.until("no tickets for your own raffle", WAIT, () -> said(bo, "your own raffle"));
            ada.run("raffle buy 1 3");
            Await.until("Ada holds three", WAIT, () -> said(ada, "You bought 3 ticket(s) for raffle #1"));
            cy.clickButtonOn("raffles off", 0);
            Await.until("Cy holds one, bought from chat", WAIT, () -> said(cy, "You bought 1 ticket(s) for raffle #1"));
            cy.run("raffle");
            cy.awaitWindow("Raffles");
            assertThat(cy.window().orElseThrow().top().values()).as("the prize in the window")
                    .anyMatch(item -> item.is("golden_apple"));
            cy.closeWindow();

            // ---- staff raffle off server money from the console; a player raffles off their own money
            server.console("eco raffle 500 5 1m");
            Await.until("the server's raffle is announced", WAIT, () -> said(ada, "The server raffles off"));
            cy.run("raffle money 100 5 1m");
            Await.until("Cy's money raffle is announced", WAIT, () -> said(ada, "Cy raffles off"));
            ada.run("raffle buy 2 1");
            Await.until("Ada is the only one in the server's raffle", WAIT, () -> said(ada, "for raffle #2"));
            bo.run("raffle buy 3 2");
            Await.until("Bo is the only one in Cy's raffle", WAIT, () -> said(bo, "for raffle #3"));
            assertThat(server.console("raffle info")).as("the console sees the raffles").contains("#2");

            // ---- giveaways: free to join, once each — a player's money, and the server's from the console
            ada.run("giveaway money 200 1m");
            Await.until("Ada's giveaway is announced", WAIT, () -> said(cy, "Ada gives away"));
            server.console("eco giveaway 300 1m");
            Await.until("the server's giveaway is announced", WAIT, () -> said(bo, "The server gives away"));
            cy.clickButtonOn("Ada gives away", 0);
            Await.until("Cy joins from chat, free", WAIT, () -> said(cy, "You joined giveaway #4"));
            bo.run("giveaway join 5");
            Await.until("Bo joins the server's", WAIT, () -> said(bo, "You joined giveaway #5"));
            bo.run("giveaway join 5");
            Await.until("but only once", WAIT, () -> said(bo, "in giveaway #5 already"));
            Await.until("the drumroll", Duration.ofSeconds(90), () -> said(ada, "The draw for"));
            Await.until("and the winner", WAIT, () -> said(ada, "[Raffle #1]") && said(ada, " wins "));
            Await.until("the winner has the apples", WAIT, () -> ada.carrying(item -> item.is("golden_apple")).isPresent()
                    || cy.carrying(item -> item.is("golden_apple")).isPresent());
            Await.until("Bo is paid the tickets less the fee", WAIT, () -> said(bo, "sold 4 ticket(s)") && said(bo, "⛃38"));
            Await.until("Ada wins the server's money", WAIT, () -> said(ada, "[Raffle #2] Ada wins ⛃500"));
            Await.until("Bo wins Cy's money", WAIT, () -> said(bo, "[Raffle #3] Bo wins ⛃100"));
            Await.until("Cy is paid for the tickets", WAIT, () -> said(cy, "Your raffle #3 sold 2 ticket(s)"));
            Await.until("Cy wins Ada's money", Duration.ofSeconds(90), () -> said(cy, "[Giveaway #4] Cy wins ⛃200"));
            Await.until("Bo wins the server's", WAIT, () -> said(bo, "[Giveaway #5] Bo wins ⛃300"));


            // ---- the wealth tax, by hand from the console: a preview first, then for real
            assertThat(server.console("eco tax 10")).contains("would take");
            bo.forgetChat();
            assertThat(server.console("eco tax 10 confirm")).contains("Wealth tax of 10% taken");
            Await.until("Bo is told what he paid", WAIT, () -> said(bo, "wealth tax") && said(bo, "You paid"));

            // ---- bets offered from what you have, not from a fixed ladder
            ada.run("eco set Ada 10000000");
            ada.run("casino");
            ada.awaitWindow("Casino");
            Await.until("a tenth of ten million", WAIT, () -> ada.window().flatMap(window -> window.slotNamed("A tenth: ⛃"))
                    .isPresent());
            assertThat(ada.window().orElseThrow().slotNamed("All in")).isPresent();
            ada.closeWindow();

            assertThat(server.paper.logLines(line -> line.contains("Exception"))).as("nothing threw").isEmpty();
        }
    }
}
