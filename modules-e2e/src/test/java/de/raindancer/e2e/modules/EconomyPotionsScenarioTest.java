package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Potions and ominous bottles in the shop on a real server: priced from brewing, bought from the Potions drawer
 * at the price it shows, sold as the real items by hand and from /sell, a stale price refused, and only the real
 * thing bought back — a potion with an extra effect, a renamed one, is not.
 */
@Tag("e2e")
class EconomyPotionsScenarioTest {

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

    /** A buy or sell price out of what {@code /eco price} printed for a key. */
    private static long printed(String output, String key, String side) {
        String plain = output.replaceAll("§.", "");
        String line = java.util.Arrays.stream(plain.split("\n")).filter(each -> each.contains(key + " ")).findFirst()
                .orElseThrow(() -> new AssertionError(key + " is not in: " + plain));
        String after = line.substring(line.indexOf(side + " ") + side.length() + 1);
        return Long.parseLong(after.split(" ")[0].replaceAll("[^0-9]", ""));
    }

    private static long amountAfter(Bot bot, String text) {
        String line = bot.chatText().stream().filter(each -> each.contains(text)).reduce((a, b) -> b).orElseThrow();
        String after = line.substring(line.indexOf(text) + text.length());
        return Long.parseLong(after.substring(after.indexOf('⛃') + 1).split("[^0-9,]")[0].replace(",", ""));
    }

    private static long count(Server server, String player, String item) {
        String answer = server.console("clear " + player + " " + item + " 0");
        if (answer.contains("No items")) {
            return 0;
        }
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("Found (\\d+)").matcher(answer);
        assertThat(found.find()).as("a count in: " + answer).isTrue();
        return Long.parseLong(found.group(1));
    }

    private static void drawer(Bot bot) {
        bot.run("shop");
        bot.awaitWindow("Shop");
        bot.click("Potions");
        bot.awaitWindow("Potions");
    }

    /** The first slot of the open drawer page holding this item, going on to the next pages until there is one. */
    private static int slotOf(Bot bot, String material) {
        for (int page = 0; page < 12; page++) {
            var found = bot.window().flatMap(window -> window.top().entrySet().stream()
                    .filter(entry -> entry.getValue().is(material)).map(Map.Entry::getKey).findFirst());
            if (found.isPresent()) {
                return found.get();
            }
            var next = bot.window().flatMap(window -> window.slotNamed("Next page"));
            if (next.isEmpty()) {
                break;
            }
            bot.clickSlot(next.get());
            Await.ticks(10);
        }
        throw new AssertionError("no " + material + " in the drawer");
    }

    @Test
    @DisplayName("potions of every kind and ominous bottles: priced, bought from the drawer, sold, nothing but the real item")
    void potions() {
        try (Server server = Server.start("economy-potions",
                List.of("economy-standalone:RainsEconomy-.*"), List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            server.console("eco give Ada 100000");

            // ---- priced like items, from brewing
            String healing = server.console("eco price potion:healing");
            long healingBuy = printed(healing, "potion:healing", "buy");
            long healingSell = printed(healing, "potion:healing", "sell");
            assertThat(healingSell).as("sold for less than bought").isLessThan(healingBuy);
            assertThat(printed(server.console("eco price potion:strong_healing"), "potion:strong_healing", "buy"))
                    .as("a glowstone more").isGreaterThan(healingBuy);
            assertThat(printed(server.console("eco price splash_potion:healing"), "splash_potion:healing", "buy"))
                    .as("and gunpowder").isGreaterThan(healingBuy);
            assertThat(printed(server.console("eco price lingering_potion:healing"), "lingering_potion:healing", "buy"))
                    .as("and dragon's breath").isGreaterThan(printed(server.console("eco price splash_potion:healing"),
                            "splash_potion:healing", "buy"));
            // Not "eco price potions": every bottle there is, which RCON cuts short.
            assertThat(server.console("eco price potion:regeneration")).contains("potion:regeneration").doesNotContain("not sold");
            assertThat(server.console("eco price ominous_bottle:5")).contains("ominous_bottle:5");
            long bad3 = printed(server.console("eco price ominous_bottle:3"), "ominous_bottle:3", "buy");
            assertThat(bad3).isEqualTo(3 * printed(server.console("eco price ominous_bottle:1"), "ominous_bottle:1", "buy"));

            // ---- the drawer: the real potions, a stale price refused, then bought at the price shown
            drawer(ada);
            int first = Await.value("a potion in the drawer", WAIT, () -> ada.window().flatMap(window -> window.top()
                    .entrySet().stream().filter(entry -> entry.getValue().is("potion")).map(Map.Entry::getKey)
                    .findFirst()).orElse(null));
            server.console("settings set economy:shop.buy-markup 2");
            ada.forgetChat();
            ada.clickSlot(first);
            expect(ada, "a stale price is refused", "no longer for sale at that price");
            assertThat(ada.carrying(item -> item.is("potion"))).isEmpty();
            server.console("settings reset economy:shop.buy-markup");
            ada.closeWindow();
            Await.ticks(40);

            drawer(ada);
            ada.forgetChat();
            ada.clickSlot(first);
            expect(ada, "a potion is bought", "You bought 1 × ");
            Await.until("and carried", WAIT, () -> ada.carrying(item -> item.is("potion")).isPresent());
            ada.forgetChat();
            ada.shiftClickSlot(first);
            expect(ada, "a handful is bought", "You bought 5 × ");
            Await.until("as five bottles", WAIT, () -> count(server, "Ada", "minecraft:potion") == 6);
            ada.closeWindow();

            // ---- sold back by hand and from /sell: less than it cost, never a profit
            server.console("clear Ada");
            Await.ticks(5);
            server.console("give Ada minecraft:potion[minecraft:potion_contents={potion:'minecraft:healing'}] 1");
            Await.until("the potion arrives", WAIT, () -> ada.carrying(item -> item.is("potion")).isPresent());
            ada.hold(ada.hotbarSlotOf(item -> item.is("potion")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            expect(ada, "it is sold", "You sold Potion of Healing for");
            assertThat(amountAfter(ada, "Potion of Healing for")).isEqualTo(healingSell);
            assertThat(count(server, "Ada", "minecraft:potion")).isZero();

            server.console("give Ada minecraft:splash_potion[minecraft:potion_contents={potion:'minecraft:long_swiftness'}] 1");
            Await.until("the splash potion arrives", WAIT, () -> ada.carrying(item -> item.is("splash_potion")).isPresent());
            ada.run("sell");
            ada.awaitWindow("Sell");
            ada.forgetChat();
            // The lot is the potion itself, named by the game rather than by the shop: found by what it is.
            ada.clickSlot(Await.value("the splash potion is offered", WAIT, () -> ada.window().flatMap(window ->
                    window.top().entrySet().stream().filter(entry -> entry.getValue().is("splash_potion"))
                            .map(Map.Entry::getKey).findFirst()).orElse(null)));
            expect(ada, "it is sold from /sell", "Splash Potion of Swiftness (long) for");
            ada.closeWindow();
            assertThat(count(server, "Ada", "minecraft:splash_potion")).isZero();

            // ---- only the real thing: an extra effect, a name, is not bought
            server.console("give Ada minecraft:potion[minecraft:potion_contents={potion:'minecraft:healing',"
                    + "custom_effects:[{id:'minecraft:speed',duration:600}]}] 1");
            server.console("give Ada minecraft:potion[minecraft:potion_contents={potion:'minecraft:healing'},"
                    + "minecraft:custom_name='\"My potion\"'] 1");
            Await.until("both arrive", WAIT, () -> count(server, "Ada", "minecraft:potion") == 2);
            ada.forgetChat();
            ada.run("sell all");
            Await.ticks(30);
            assertThat(count(server, "Ada", "minecraft:potion")).as("neither was bought").isEqualTo(2);
            server.console("clear Ada");

            // ---- ominous bottles: sold at the level they are, bought from the drawer's last page
            server.console("give Ada minecraft:ominous_bottle[minecraft:ominous_bottle_amplifier=2] 1");
            Await.until("the bottle arrives", WAIT, () -> ada.carrying(item -> item.is("ominous_bottle")).isPresent());
            long bad3Sells = printed(server.console("eco price ominous_bottle:3"), "ominous_bottle:3", "sell");
            ada.hold(ada.hotbarSlotOf(item -> item.is("ominous_bottle")));
            Await.ticks(5);
            ada.forgetChat();
            ada.run("sell hand");
            expect(ada, "it is sold", "Ominous Bottle (Bad Omen III) for");
            assertThat(amountAfter(ada, "Bad Omen III) for")).isEqualTo(bad3Sells);

            drawer(ada);
            int bottle = slotOf(ada, "ominous_bottle");
            ada.forgetChat();
            ada.clickSlot(bottle);
            expect(ada, "an ominous bottle is bought", "You bought 1 × Ominous Bottle (Bad Omen");
            Await.until("and carried", WAIT, () -> ada.carrying(item -> item.is("ominous_bottle")).isPresent());
            ada.closeWindow();

            // ---- closing them takes them out of the drawer, buying stays closed, selling goes on
            server.console("settings set economy:shop.potions false");
            assertThat(server.console("eco price potion:healing")).contains("not sold");
            server.console("settings reset economy:shop.potions");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy")).isEmpty();
        }
    }
}
