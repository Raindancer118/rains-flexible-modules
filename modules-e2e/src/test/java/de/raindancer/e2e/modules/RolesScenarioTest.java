package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Roles and packs on a real server: a role picked, its discount paid at the shop, the wait, staff's bypass, packs. */
@Tag("e2e")
class RolesScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Pattern BOUGHT = Pattern.compile("You bought 16 × Cooked Beef for ⛃([0-9,]+)");

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static boolean lore(Bot bot, String button, String text) {
        return bot.window().flatMap(window -> window.slotNamed(button).map(slot -> window.top().get(slot)))
                .map(item -> item.lore().stream().anyMatch(line -> line.contains(text))).orElse(false);
    }

    private static void clickExact(Bot bot, String name) {
        int slot = Await.value(() -> bot.name() + " sees a button named exactly " + name, WAIT, () -> bot.window()
                .flatMap(window -> window.top().entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                        .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
        bot.clickSlot(slot);
    }

    /** Buys 16 cooked beef from its trade window and says what it cost. */
    private static long buySixteenBeef(Bot bot) {
        bot.forgetChat();
        // The harness now and then loses a click into a window it has only just been sent; retried until bought.
        for (int attempt = 0; attempt < 3 && bot.chatText().stream().noneMatch(line -> BOUGHT.matcher(line).find()); attempt++) {
            bot.run("shop cooked_beef");
            bot.awaitWindow("Cooked Beef");
            Await.ticks(10);
            clickExact(bot, "Buy 16");
            Await.ticks(40);
        }
        String line = Await.value(() -> bot.name() + " buys beef (heard " + bot.chatText() + ")", WAIT,
                () -> bot.chatText().stream().filter(text -> BOUGHT.matcher(text).find()).findFirst().orElse(null));
        bot.closeWindow();
        Matcher found = BOUGHT.matcher(line);
        assertThat(found.find()).isTrue();
        return Long.parseLong(found.group(1).replace(",", ""));
    }

    @Test
    @DisplayName("a cook pays a quarter less for food, waits to change, staff bypass; a pack is bought and unpacked")
    void roles() {
        try (Server server = Server.start("roles", List.of("economy-standalone:RainsEconomy-.*",
                "roles-standalone:RainsRoles-.*"), List.of("The economy is up", "Roles are up"))) {
            server.console("settings set economy:features.dynamic-prices false");
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Await.ticks(20);

            // ---- the roles screen: every role, clickable for somebody without one
            bo.run("role");
            bo.awaitWindow("Roles");
            Await.until("Bo sees the cook", WAIT, () -> lore(bo, "Cook", "Click to become"));
            assertThat(bo.window().orElseThrow().slotNamed("Builder")).isPresent();
            assertThat(bo.window().orElseThrow().slotNamed("Explorer")).isPresent();
            assertThat(bo.window().orElseThrow().slotNamed("Mage")).isPresent();
            bo.closeWindow();

            // ---- before any role: Bo pays what everybody pays
            long everybody = buySixteenBeef(bo);

            // ---- Bo becomes a cook; Ada hears it
            ada.forgetChat();
            bo.forgetChat();
            bo.run("role cook");
            Await.until(() -> "Bo is a cook (heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "You are a Cook now") && said(bo, "change again in"));
            Await.until("Ada is told", WAIT, () -> said(ada, "Bo is a Cook now"));

            // ---- the shop shows the price everybody pays crossed out, the cook's next to it, and charges that
            bo.run("shop cooked_beef");
            bo.awaitWindow("Cooked Beef");
            Await.until("the button shows the cook's price", WAIT, () -> lore(bo, "Buy 16", "Cook")
                    && lore(bo, "Buy 16", "25%"));
            String line = bo.window().flatMap(window -> window.slotNamed("Buy 16").map(slot -> window.top().get(slot)))
                    .orElseThrow().lore().stream().filter(text -> text.contains("Cook")).findFirst().orElseThrow();
            Matcher amounts = Pattern.compile("⛃([0-9,]+)").matcher(line);
            assertThat(amounts.find()).as(line).isTrue();
            long shown = Long.parseLong(amounts.group(1).replace(",", ""));
            assertThat(amounts.find()).as(line).isTrue();
            long cooks = Long.parseLong(amounts.group(1).replace(",", ""));
            assertThat(shown).as("the crossed-out price is everybody's, as Bo paid before (%s)", line)
                    .isGreaterThanOrEqualTo(everybody);
            assertThat(cooks).as("a quarter off the whole line, rounded up (%s)", line)
                    .isEqualTo((shown * 75 + 99) / 100);
            bo.closeWindow();
            long cook = buySixteenBeef(bo);
            assertThat(cook).as("charged what the button said").isEqualTo(cooks);

            // ---- three days before changing; same role is no change
            bo.forgetChat();
            bo.run("role builder");
            Await.until("too soon", WAIT, () -> said(bo, "Not yet"));
            bo.forgetChat();
            bo.run("role cook");
            Await.until("already", WAIT, () -> said(bo, "already"));

            // ---- staff: bypass, change at once and again, quietly
            ada.forgetChat();
            ada.run("role bypass");
            Await.until("bypass on", WAIT, () -> said(ada, "Bypass on"));
            bo.forgetChat();
            ada.run("role mage");
            Await.until("Ada is a mage", WAIT, () -> said(ada, "You are a Mage now"));
            ada.run("role explorer");
            Await.until("and at once an explorer", WAIT, () -> said(ada, "You are an Explorer now"));
            Await.ticks(10);
            assertThat(said(bo, "Ada is")).as("a bypassed change is not announced").isFalse();

            // ---- staff set somebody's role and look it up
            ada.forgetChat();
            ada.run("role set Bo builder");
            Await.until("Bo is made a builder", WAIT, () -> said(ada, "Bo is a Builder now")
                    && said(bo, "Staff made you a Builder"));
            ada.run("role info Bo");
            Await.until("info says builder", WAIT, () -> said(ada, "Bo is a Builder"));

            // ---- packs: in the shop, bought, unpacked
            ada.run("eco give Bo 20000");
            Await.ticks(10);
            bo.forgetChat();
            for (int attempt = 0; attempt < 3 && !said(bo, "You bought the Explorer's Pack"); attempt++) {
                bo.run("shop");
                bo.awaitWindow("Shop");
                Await.ticks(10);
                clickExact(bo, "Packs");
                bo.awaitWindow("Packs");
                Await.until("the explorer's pack has a price", WAIT, () -> lore(bo, "Explorer's Pack", "Price"));
                List<String> unpriced = bo.window().orElseThrow().top().values().stream()
                        .filter(item -> item.lore().stream().anyMatch(text -> text.contains("has no price")))
                        .map(item -> item.name()).toList();
                assertThat(unpriced).as("every shipped pack can be bought").isEmpty();
                Await.ticks(10);
                clickExact(bo, "Explorer's Pack");
                Await.ticks(40);
            }
            Await.until(() -> "the pack is bought (heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "You bought the Explorer's Pack"));
            bo.closeWindow();
            Await.until("Bo carries the pack", WAIT, () -> bo.carrying(item -> item.tag("pack").isPresent()).isPresent());
            bo.forgetChat();
            bo.use(item -> item.tag("pack").isPresent());
            Await.until(() -> "the pack opens (heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "You unpacked the Explorer's Pack"));
            Await.until("its torches are there", WAIT, () -> bo.carrying(item -> item.is("torch")).isPresent());
            assertThat(bo.carrying(item -> item.tag("pack").isPresent())).as("the pack is used up").isEmpty();

            // ---- the starter pack is one each
            bo.forgetChat();
            for (int attempt = 0; attempt < 3 && !said(bo, "You bought the Starter Pack"); attempt++) {
                bo.run("shop");
                bo.awaitWindow("Shop");
                Await.ticks(10);
                clickExact(bo, "Packs");
                bo.awaitWindow("Packs");
                Await.ticks(10);
                clickExact(bo, "Starter Pack");
                Await.ticks(40);
            }
            Await.until("the starter pack is bought", WAIT, () -> said(bo, "You bought the Starter Pack"));
            Await.until("and now shows as had", WAIT, () -> lore(bo, "Starter Pack", "one each"));
            bo.forgetChat();
            clickExact(bo, "Starter Pack");
            Await.until("a second is refused", WAIT, () -> said(bo, "It is one each"));
            bo.closeWindow();
        }
    }
}
