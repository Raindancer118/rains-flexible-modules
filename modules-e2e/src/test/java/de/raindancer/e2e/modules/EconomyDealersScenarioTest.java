package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gambling in person: with the casino's dealers-only switch on, no game is played by command or from the casino
 * screen — each game has its own dealer standing in the world. With Core's insurance switch on, bets, death and
 * items are insured at an insurance agent and nowhere else.
 */
@Tag("e2e")
class EconomyDealersScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Pattern INTS = Pattern.compile("\\[I;\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)]");

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void set(Server server, String key, String value) {
        assertThat(server.console("settings set " + key + " " + value)).as(key).contains("is now");
        Await.ticks(20);
    }

    /** The UUID of the mannequin carrying this PDC entry, read off the server. */
    private static UUID attendant(Server server, String key, String value) {
        String answer = Await.value("a mannequin with " + key + "=" + value, WAIT, () -> {
            String out = server.console("data get entity @e[type=minecraft:mannequin,limit=1,nbt={BukkitValues:{\""
                    + key + "\":\"" + value + "\"}}] UUID");
            return INTS.matcher(out).find() ? out : null;
        });
        Matcher ints = INTS.matcher(answer);
        assertThat(ints.find()).isTrue();
        long most = ((long) Integer.parseInt(ints.group(1)) << 32) | (Integer.parseInt(ints.group(2)) & 0xffffffffL);
        long least = ((long) Integer.parseInt(ints.group(3)) << 32) | (Integer.parseInt(ints.group(4)) & 0xffffffffL);
        return new UUID(most, least);
    }

    @Test
    @DisplayName("dealers only: every game at its own dealer, duels at the coin flip table, insurance at an agent")
    void inPerson() {
        try (Server server = Server.start("economy-dealers",
                List.of("economy-standalone:RainsEconomy-.*", "invsnap-standalone:RainsInventorySnapshots-.*"),
                List.of("The economy is up", "Inventory snapshots are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            set(server, "gamble.cooldown-seconds", "0");
            set(server, "gamble.insurance", "true");
            set(server, "insurance.enabled", "true");

            // ---- before the switch, commands play as always
            bo.forgetChat();
            bo.run("coinflip");
            bo.awaitWindow("Coin flip");
            bo.closeWindow();

            set(server, "casino.dealers-only", "true");

            // ---- no game by command
            for (String command : List.of("coinflip 10 heads", "dice 10 over 50", "slots", "roulette", "blackjack",
                    "baccarat", "hilo", "mines", "crash", "race", "scratch buy 1", "lottery buy 1")) {
                bo.forgetChat();
                bo.run(command);
                Await.until("/" + command + " sends Bo to a dealer", WAIT, () -> said(bo, "dealer"));
            }
            assertThat(bo.window()).as("no game window opened").isEmpty();

            // ---- the casino screen still opens, but deals nothing
            bo.run("casino");
            bo.awaitWindow("Casino");
            int coinflip = Await.value("the coin flip door", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Coin flip")).orElse(null));
            assertThat(bo.window().orElseThrow().top().get(coinflip).lore())
                    .anyMatch(line -> line.contains("Coin flip dealer"));
            bo.clickSlot(coinflip);
            Await.ticks(10);
            assertThat(bo.window().orElseThrow().title()).contains("Casino");
            bo.closeWindow();

            // ---- a coin flip dealer: right click it and the game opens
            ada.run("eco dealer place coinflip");
            UUID dealer = attendant(server, "rainseconomy:dealer", "COINFLIP");
            assertThat(server.console("data get entity @e[type=minecraft:mannequin,limit=1,nbt={BukkitValues:"
                    + "{\"rainseconomy:dealer\":\"COINFLIP\"}}] profile.properties[0].value"))
                    .as("the dealer wears the shipped, signed suit").contains("ewogICJ0aW1lc3RhbXAiIDogMTcwMTE5MTAzMjkx");
            server.console("tp Bo Ada");
            Await.until("Bo is shown the dealer", WAIT, () -> bo.sees(dealer));
            bo.interact(dealer);
            bo.awaitWindow("Coin flip");
            bo.closeWindow();

            // ---- a dice dealer and a scratch card seller exist too; a dealer looks the way it was placed looking
            server.console("execute as Ada at @s run tp @s ~ ~ ~ 123 30");
            Await.ticks(10);
            ada.forgetChat();
            ada.run("eco dealer place dice");
            Await.until("a dice dealer", WAIT, () -> said(ada, "Dice"));
            assertThat(server.console("data get entity @e[type=minecraft:mannequin,limit=1,nbt={BukkitValues:"
                    + "{\"rainseconomy:dealer\":\"DICE\"}}] Rotation"))
                    .as("the dice dealer looks where Ada looked, level").contains("123.0f, 0.0f");
            ada.forgetChat();
            ada.run("eco dealer place scratch");
            Await.until("a scratch card seller", WAIT, () -> said(ada, "Scratch"));
            bo.interact(attendant(server, "rainseconomy:dealer", "SCRATCH"));
            bo.awaitWindow("Scratch");
            bo.closeWindow();

            // ---- a duel is called at the coin flip table, and only there
            server.console("execute as Bo at @s run tp @s ~40 ~ ~");
            bo.forgetChat();
            bo.run("coinflip Ada 10");
            Await.until("away from the table, no duel", WAIT, () -> said(bo, "Coin flip dealer"));
            server.console("tp Bo Ada");
            Await.ticks(10);
            bo.forgetChat();
            ada.forgetChat();
            bo.run("coinflip Ada 10");
            Await.until("at the table, the duel is offered", WAIT, () -> said(ada, "Bo"));

            // ---- insurance in person: not by command, not from the casino
            set(server, "core:insurance-in-person-only", "true");
            bo.forgetChat();
            bo.run("casino insure");
            Await.until("bet insurance is not arranged by command", WAIT, () -> said(bo, "Insurance agent"));
            bo.forgetChat();
            bo.run("insurance on");
            Await.until("nor is death insurance", WAIT, () -> said(bo, "Insurance agent"));

            // ---- an insurance agent sells both
            ada.run("eco agent place");
            UUID agent = attendant(server, "rainscore:desk", "INSURANCE");
            assertThat(server.console("data get entity @e[type=minecraft:mannequin,limit=1,nbt={BukkitValues:"
                    + "{\"rainscore:desk\":\"INSURANCE\"}}] profile.properties[0].value"))
                    .as("so does the agent").contains("ewogICJ0aW1lc3RhbXAiIDogMTcwMTE5MTAzMjkx");
            Await.until("Bo is shown the agent", WAIT, () -> bo.sees(agent));
            bo.interact(agent);
            bo.awaitWindow("Insurance");
            int bets = Await.value("the bet insurance button", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Insure my bets")).orElse(null));
            int inventory = Await.value("the inventory insurance button", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Death and item insurance")).orElse(null));
            bo.clickSlot(bets);
            Await.until("bets are insured now", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Insure my bets: on")).isPresent());
            bo.clickSlot(inventory);
            Await.until("the inventory insurance screen opens at the agent", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Death insurance: ")).isPresent());
            int death = bo.window().flatMap(window -> window.slotNamed("Death insurance: ")).orElseThrow();
            bo.clickSlot(death);
            Await.until("and death insurance is switched on there", WAIT, () -> bo.window()
                    .flatMap(window -> window.slotNamed("Death insurance: on")).isPresent());

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy", "RainsInventorySnapshots")).isEmpty();
        }
    }
}
