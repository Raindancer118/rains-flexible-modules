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
 * Sends on a real server, between real clients: the window, shift-click into it, the chat button, no room,
 * cancelling, running out (with the sender online and away), and surviving a restart.
 *
 * <p>Every count is asked of the server with {@code clear <player> <item> 0}, not of the bot's picture of its
 * inventory, so "never doubled" is what the server says.
 */
@Tag("e2e")
class SendsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** Hotbar slot n in the five-row send window: 45 top slots, then 27 main, then the hotbar. */
    private static int hotbar(int n) {
        return 72 + n;
    }

    private static int count(Server server, String player, String item) {
        String answer = server.console("clear " + player + " minecraft:" + item + " 0");
        if (answer.contains("No items")) {
            return 0;
        }
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("Found (\\d+)").matcher(answer);
        assertThat(found.find()).as("a count in: " + answer).isTrue();
        return Integer.parseInt(found.group(1));
    }

    private static boolean topHas(Bot bot, String material) {
        return bot.window().map(window -> window.top().entrySet().stream()
                .anyMatch(entry -> entry.getKey() < 27 && entry.getValue().is(material))).orElse(false);
    }

    private static boolean told(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    /** Ada puts these in a window for Bo and presses Send. */
    private static void send(Server server, Bot from, String to, String item, int amount) {
        server.console("item replace entity " + from.name() + " hotbar.0 with minecraft:" + item + " " + amount);
        Await.ticks(5);
        from.run("send create " + to);
        from.awaitWindow("Send to");
        from.shiftClickSlot(hotbar(0));
        Await.until("the " + item + " is in the window", WAIT, () -> topHas(from, item));
        from.click("Send it");
        Await.until("the window closes", WAIT, () -> from.window().isEmpty());
    }

    @Test
    @DisplayName("send, accept by chat button, no room, /sends, cancel, decline, run out, restart")
    void sends() {
        try (Server server = Server.start("sends", List.of("sends-standalone:RainsSends-.*"), List.of(),
                Map.of("plugins/RainsSends/config.yml", "expiry-minutes: 1\ncooldown-seconds: 0\n"),
                List.of("Sends are up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");

            // ---- a send, shift-clicked in, accepted by the button in chat
            server.console("item replace entity Ada hotbar.1 with minecraft:diamond 3");
            ada.forgetChat();
            bo.forgetChat();
            send(server, ada, "Bo", "dirt", 64);
            assertThat(count(server, "Ada", "dirt")).as("the dirt left Ada").isZero();
            Await.until("Bo is told", WAIT, () -> told(bo, "wants to send you"));
            Await.until("Ada is told", WAIT, () -> told(ada, "Sent"));
            assertThat(count(server, "Bo", "dirt")).as("nothing has arrived before it is accepted").isZero();

            bo.clickChat("/send accept");
            Await.until("Bo has the dirt", WAIT, () -> count(server, "Bo", "dirt") == 64);
            Await.until("Ada is told it was accepted", WAIT, () -> told(ada, "accepted your send"));
            bo.forgetChat();
            bo.run("send accept");
            Await.until("a second accept finds nothing", WAIT, () -> told(bo, "Nothing is waiting"));
            assertThat(count(server, "Bo", "dirt")).as("exactly once").isEqualTo(64);
            assertThat(count(server, "Ada", "dirt")).isZero();

            // ---- closing the window without sending gives everything back
            server.console("item replace entity Ada hotbar.0 with minecraft:stone 5");
            Await.ticks(5);
            ada.run("send create Bo");
            ada.awaitWindow("Send to");
            ada.shiftClickSlot(hotbar(0));
            Await.until("the stone is in the window", WAIT, () -> topHas(ada, "stone"));
            ada.closeWindow();
            Await.until("the stone is back", WAIT, () -> count(server, "Ada", "stone") == 5);

            // ---- yourself, and nothing, are refused
            ada.forgetChat();
            ada.run("send create Ada");
            Await.until("told off", WAIT, () -> told(ada, "yourself"));
            server.console("clear Ada");
            Await.ticks(5);
            ada.run("send create Bo");
            ada.awaitWindow("Send to");
            ada.click("Send it");
            Await.until("nothing to send", WAIT, () -> told(ada, "Put something in the window"));
            ada.closeWindow();

            // ---- no room: it waits, /sends lists it, and a click takes it once there is room
            for (int slot = 0; slot < 36; slot++) {
                server.console("item replace entity Bo container." + slot + " with minecraft:cobblestone 64");
            }
            Await.ticks(5);
            bo.forgetChat();
            send(server, ada, "Bo", "cactus", 3);
            Await.until("Bo is told", WAIT, () -> told(bo, "wants to send you"));
            bo.clickChat("/send accept");
            Await.until("Bo is told there is no room", WAIT, () -> told(bo, "no room"));
            assertThat(count(server, "Bo", "cactus")).isZero();

            bo.run("sends");
            bo.awaitWindow("Sends");
            bo.click("From Ada");
            Await.ticks(20);
            assertThat(count(server, "Bo", "cactus")).as("still no room").isZero();
            server.console("clear Bo minecraft:cobblestone 128");
            Await.ticks(5);
            bo.click("From Ada");
            Await.until("the cactus arrives", WAIT, () -> count(server, "Bo", "cactus") == 3);
            bo.closeWindow();
            server.console("clear Bo");

            // ---- cancelling takes it back, and the other side cannot accept it any more
            bo.forgetChat();
            send(server, ada, "Bo", "apple", 2);
            Await.until("Bo is told", WAIT, () -> told(bo, "wants to send you"));
            ada.run("send cancel");
            Await.until("the apples are back with Ada", WAIT, () -> count(server, "Ada", "apple") == 2);
            bo.run("send accept");
            Await.ticks(20);
            assertThat(count(server, "Bo", "apple")).isZero();
            assertThat(count(server, "Ada", "apple")).isEqualTo(2);

            // ---- declining sends it back
            server.console("clear Ada");
            send(server, ada, "Bo", "bread", 4);
            Await.until("Bo is told", WAIT, () -> told(bo, "wants to send you"));
            bo.run("send decline");
            Await.until("the bread is back with Ada", WAIT, () -> count(server, "Ada", "bread") == 4);
            assertThat(count(server, "Bo", "bread")).isZero();

            // ---- survives a restart: nothing is lost on the way, nothing is doubled
            server.console("clear Ada");
            send(server, ada, "Bo", "iron_ingot", 7);
            assertThat(count(server, "Ada", "iron_ingot")).isZero();
            server.paper.restart();
            bo.rejoin();
            Await.until("Bo is reminded after the restart", Duration.ofSeconds(30), () -> told(bo, "waiting for you"));
            bo.run("send accept");
            Await.until("the iron arrives after the restart", WAIT, () -> count(server, "Bo", "iron_ingot") == 7);
            ada.rejoin();
            assertThat(count(server, "Ada", "iron_ingot")).isZero();
            server.console("clear Bo");

            // ---- running out: online sender gets it back live, an absent one on joining
            cy.rejoin();
            send(server, ada, "Bo", "gold_ingot", 4);
            send(server, cy, "Bo", "copper_ingot", 6);
            cy.leave();
            Await.until("Ada's gold comes back when it runs out", Duration.ofSeconds(120),
                    () -> count(server, "Ada", "gold_ingot") == 4);
            Await.until("Ada is told", WAIT, () -> told(ada, "not accepted in time"));
            bo.run("send accept");
            Await.ticks(20);
            assertThat(count(server, "Bo", "gold_ingot")).as("a send that ran out cannot be accepted").isZero();
            cy.rejoin();
            Await.until("Cy gets the copper back on joining", Duration.ofSeconds(30),
                    () -> count(server, "Cy", "copper_ingot") == 6);

            assertThat(server.paper.errorsFrom("RainsCore", "RainsSends")).isEmpty();
        }
    }
}
