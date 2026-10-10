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

/**
 * Roles' abilities, personal quests, advancements paying by difficulty and mob drops in the shop, on a real
 * server with economy, roles and jobs together.
 */
@Tag("e2e")
class QuestsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int Y = 71;
    private static final String PICKAXE =
            "minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":10}]";
    private static final String DIAMONDS = "A glint in the dark";

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static boolean lore(Bot bot, String button, String text) {
        return bot.window().flatMap(window -> window.slotNamed(button).map(slot -> window.top().get(slot)))
                .map(item -> item.lore().stream().anyMatch(line -> line.contains(text))).orElse(false);
    }

    private static String loreOf(Bot bot, String button) {
        return bot.window().flatMap(window -> window.slotNamed(button).map(slot -> window.top().get(slot)))
                .map(item -> String.join(" | ", item.lore())).orElse("(none)");
    }

    /** How many offers the open window holds. */
    private static long offers(Bot bot) {
        return bot.window().map(window -> window.top().values().stream()
                .filter(item -> item.lore().stream().anyMatch(line -> line.contains("Click to take it"))).count()).orElse(0L);
    }

    private static double speed(Server server) {
        String answer = server.console("attribute Mia minecraft:movement_speed get");
        Matcher number = Pattern.compile("is (-?[0-9.]+)").matcher(answer);
        assertThat(number.find()).as("an attribute value in: %s", answer).isTrue();
        return Double.parseDouble(number.group(1));
    }

    @Test
    @DisplayName("an explorer is faster; a miner's quest counts mined ore but not placed ore and pays; advancements pay by difficulty; mob drops sell; an order is offered, taken and given up")
    void quests() {
        try (Server server = Server.start("quests", List.of("economy-standalone:RainsEconomy-.*",
                "roles-standalone:RainsRoles-.*", "jobs-standalone:RainsJobs-.*"),
                List.of("The economy is up", "Roles are up", "The job board is up"))) {
            server.console("settings set economy:features.dynamic-prices false");
            server.console("forceload add -16 -16 16 16");
            server.console("fill -6 " + (Y - 3) + " -6 6 " + (Y - 1) + " 6 minecraft:stone");
            server.console("fill -6 " + Y + " -6 6 " + (Y + 3) + " 6 minecraft:air");
            Bot mia = server.player("Mia");
            server.console("gamemode survival Mia");
            server.console("tp Mia 0.5 " + Y + " 0.5");
            Await.ticks(20);

            // ---- abilities: an explorer is a little faster on foot, and stops being when the role changes
            double base = speed(server);
            server.console("role set Mia explorer");
            Await.until("an explorer is faster", WAIT, () -> speed(server) > base + 0.0005);
            assertThat(speed(server)).as("only a little").isLessThan(base * 1.11);
            server.console("role set Mia miner");
            Await.until("a miner is not", WAIT, () -> Math.abs(speed(server) - base) < 1e-6);

            // ---- a miner's quest, given by staff: 4 diamond ore at tier 0, with the role's quarter more pay
            // No random quests today, so the one given is the only one to watch.
            server.console("settings set jobs-quests:per-day 0");
            server.console("settings set jobs-quests:per-day-for-role 0");
            server.console("quests reset Mia");
            String given = server.console("quests give Mia miner-diamonds");
            assertThat(given).contains(DIAMONDS).contains("4 to go");

            // Ore Mia puts down herself does not count.
            server.console("clear Mia");
            server.console("give Mia minecraft:diamond_ore 1");
            Await.ticks(10);
            mia.hold(0);
            mia.useOn(1, Y - 1, 2);
            Await.until("Mia placed the ore", WAIT,
                    () -> server.console("execute if block 1 " + Y + " 2 minecraft:diamond_ore").contains("passed"));
            server.console("item replace entity Mia weapon.mainhand with " + PICKAXE);
            Await.ticks(10);
            mia.dig(1, Y, 2);
            Await.until("the placed ore is mined", WAIT,
                    () -> server.console("execute if block 1 " + Y + " 2 minecraft:air").contains("passed"));
            Await.ticks(10);
            mia.run("quests");
            mia.awaitWindow("Your quests");
            Await.until(() -> "the placed ore did not count (" + loreOf(mia, DIAMONDS) + ")", WAIT,
                    () -> lore(mia, DIAMONDS, " 0/4"));
            mia.closeWindow();

            // Ore that was there counts, and the fourth pays at once.
            server.console("fill 3 " + Y + " -1 3 " + Y + " 2 minecraft:diamond_ore");
            mia.forgetChat();
            for (int z = -1; z <= 2; z++) {
                mia.dig(3, Y, z);
                Await.ticks(10);
            }
            Await.until(() -> "the quest pays (heard " + mia.chatText() + ")", WAIT,
                    () -> said(mia, "Quest done: " + DIAMONDS) && said(mia, "⛃325"));

            // ---- advancements: told what each paid, the hard ones more
            mia.forgetChat();
            server.console("advancement grant Mia only minecraft:story/mine_stone");
            Await.until(() -> "a task pays 250 (heard " + mia.chatText() + ")", WAIT,
                    () -> said(mia, "⛃250") && said(mia, "for the advancement Stone Age"));
            server.console("advancement grant Mia only minecraft:nether/return_to_sender");
            Await.until(() -> "a challenge pays 2,000 (heard " + mia.chatText() + ")", WAIT,
                    () -> said(mia, "⛃2,000") && said(mia, "Return to Sender"));

            // ---- mob drops trade both ways; the sell price says why it is what it is
            mia.run("shop rotten_flesh");
            mia.awaitWindow("Rotten Flesh");
            Await.until(() -> "rotten flesh is bought and sold (" + loreOf(mia, "Rotten Flesh") + ")", WAIT,
                    () -> lore(mia, "Rotten Flesh", "Sell one:") && lore(mia, "Rotten Flesh", "Buy one:"));
            mia.closeWindow();
            mia.run("shop diamond");
            mia.awaitWindow("Diamond");
            Await.until(() -> "the miner's bonus is under the sell price (" + loreOf(mia, "Diamond") + ")", WAIT,
                    () -> lore(mia, "Diamond", "Miner: +"));
            mia.closeWindow();

            // ---- orders: name an amount, get work and a clock; too much is refused
            mia.forgetChat();
            mia.run("quests ask 200b");
            mia.expectChat("Nobody pays more than");
            mia.run("quests ask 100m");
            mia.awaitWindow("How long?");
            Await.until("the times are offered", WAIT, () -> mia.window().orElseThrow().slotNamed("Let the work decide").isPresent());
            Await.ticks(10);
            mia.clickSlot(mia.window().orElseThrow().slotNamed("Let the work decide").orElseThrow());
            mia.awaitWindow("Pick your work");
            Await.until(() -> "several offers, all paying a hundred million (" + mia.window().map(window -> window.top()
                    .toString()).orElse("none") + ")", WAIT, () -> offers(mia) >= 3
                    && mia.window().orElseThrow().slotNamed("⛃100,000,000").isPresent());
            mia.closeWindow();
            mia.forgetChat();
            mia.run("quests ask 2k 1h");
            mia.awaitWindow("Pick your work");
            Await.until("offers in the hour chosen", WAIT, () -> offers(mia) >= 3
                    && lore(mia, "Each pays", "Each in 1 hour"));
            Await.ticks(10);
            mia.clickSlot(mia.window().orElseThrow().top().entrySet().stream()
                    .filter(entry -> entry.getValue().lore().stream().anyMatch(line -> line.contains("Click to take it")))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow());
            Await.until(() -> "the order starts (heard " + mia.chatText() + ")", WAIT,
                    () -> said(mia, "You have") && said(mia, "Go!"));
            mia.run("quests ask 2k");
            mia.expectChat("You are on an order already");
            mia.run("quests cancel");
            mia.expectChat("Order given up");

            // ---- a free swap of one of the day's quests
            server.console("settings set jobs-quests:per-day 3");
            server.console("quests reset Mia");
            mia.run("quests");
            mia.awaitWindow("Your quests");
            int swappable = Await.value(() -> "a quest that can be swapped (" + mia.window().map(window -> window.top()
                    .toString()).orElse("none") + ")", WAIT, () -> mia.window().flatMap(window -> window.top().entrySet()
                    .stream().filter(entry -> entry.getValue().lore().stream().anyMatch(line -> line.contains("to swap it")))
                    .map(java.util.Map.Entry::getKey).findFirst()).orElse(null));
            mia.clickSlot(swappable);
            mia.awaitWindow("Swap this quest?");
            Await.ticks(10);
            mia.forgetChat();
            mia.clickSlot(mia.window().orElseThrow().slotNamed("Yes, do it").orElseThrow());
            mia.expectChat("Swapped.");
            mia.closeWindow();

            // ---- back pay, once, for an advancement made while advancements did not pay
            server.console("settings set economy:features.advancement-rewards false");
            server.console("advancement grant Mia only minecraft:story/smelt_iron");
            Await.ticks(20);
            server.console("settings set economy:features.advancement-rewards true");
            mia.forgetChat();
            mia.run("claimadvancements");
            Await.until(() -> "back pay for the one unpaid advancement (heard " + mia.chatText() + ")", WAIT,
                    () -> said(mia, "Back pay for 1 advancement(s)") && said(mia, "⛃250"));
            mia.run("claimadvancements");
            mia.expectChat("claimed your back pay already");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy", "RainsRoles", "RainsJobs")).isEmpty();
        }
    }
}
