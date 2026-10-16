package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.LocalPostgres;
import de.raindancer.e2e.PaperServer;
import de.raindancer.e2e.VelocityProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /shop} on the island server for an island that plays with the SMP economy: the SMP keeps the money and the
 * shop (the bank), the island server runs RainsEconomy as a client with {@code shop-here}. What is proved: the bank's
 * prices show there, a purchase is charged by the bank and the items arrive, a sale is paid by the bank and the items
 * leave, and a Pure island is kept out of it — all across a real PostgreSQL network.
 */
@Tag("e2e")
class OneBlockShopScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String FORWARDING_SECRET = "e2e-forwarding-secret";

    @Test
    @DisplayName("an SMP-economy island buys and sells through the bank's shop; a Pure island does not")
    void shop() throws Exception {
        String paperGlobal = VelocityProxy.paperGlobal(FORWARDING_SECRET);
        LocalPostgres postgres = LocalPostgres.start(E2e.serverFolder("oneblock-shop-postgres"));
        Path economy = OneBlockNetworkScenarioTest.jar("economy-standalone", "RainsEconomy-.*");
        Path oneBlock = OneBlockNetworkScenarioTest.jar("oneblock-standalone", "RainsOneBlock-.*");
        PaperServer smp = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-shop-smp"))
                .plugin(Path.of(System.getProperty("e2e.rainscore"))).plugin(economy).plugin(oneBlock)
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/network.yml", postgres.networkYml("smp", "survival"))
                .build();
        PaperServer island = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-shop-island"))
                .plugin(Path.of(System.getProperty("e2e.rainscore"))).plugin(economy).plugin(oneBlock)
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/network.yml", postgres.networkYml("oneblock", "minigames"))
                .file("plugins/RainsEconomy/config.yml", "\"~overrides\": true\nshop-here: true\n")
                .build();
        Map<String, Integer> servers = new LinkedHashMap<>();
        servers.put("smp", smp.port());
        servers.put("oneblock", island.port());
        try (VelocityProxy proxy = VelocityProxy.in(E2e.serverFolder("oneblock-shop-proxy"), servers, FORWARDING_SECRET);
             smp; island; postgres) {
            // The SMP first: the first server with an economy of its own becomes the network's bank.
            smp.start();
            smp.awaitLog("The economy is up", WAIT);
            Await.ticks(160);
            island.start();
            island.awaitLog("and so do /shop and /sell", WAIT);
            proxy.start();

            Bot ada = smp.bot("Ada").through(proxy.port()).join();
            smp.console("eco give Ada 5000");
            ada.run("island");
            Await.until("Ada is on the island server", WAIT, () -> island.console("list").contains("Ada"));
            choose(ada, "With the SMP economy");
            ada.expectChat("Your island is ready");

            // ── the bank's shop, found by a search, one item bought and some of it sold back ───────────────────
            ada.run("shop");
            int search = slot(ada, "Search");
            ada.clickSlot(search);
            Await.ticks(10);
            ada.typeInAnvil("cobblestone");
            int cobble = slot(ada, "Cobblestone");
            ada.clickSlot(cobble);
            int buy = slot(ada, "Buy 16");
            ada.clickSlot(buy);
            ada.expectChat("You bought 16");
            Await.until("sixteen cobblestone arrived", WAIT, () -> carried(island, "Ada", "cobblestone") == 16);
            int sell = slot(ada, "Sell 8");
            ada.clickSlot(sell);
            ada.expectChat("You sold");
            Await.until("eight went to the bank", WAIT, () -> carried(island, "Ada", "cobblestone") == 8);
            ada.closeWindow();
            ada.run("balance");
            ada.expectChat("the bank is on");

            // ── a Pure island does not get the SMP's shop ──────────────────────────────────────────────────────
            Bot bo = smp.bot("Bo").through(proxy.port()).join();
            bo.run("island");
            Await.until("Bo is on the island server", WAIT, () -> island.console("list").contains("Bo"));
            choose(bo, "Pure OneBlock");
            bo.expectChat("Your island is ready");
            bo.run("shop");
            bo.expectChat("plays Pure");

            assertThat(errors(island)).isEmpty();
            assertThat(errors(smp)).isEmpty();
        } finally {
            smp.keepLogsIn(E2e.logsFolder("oneblock-shop-smp"));
            island.keepLogsIn(E2e.logsFolder("oneblock-shop-island"));
        }
    }

    private static void choose(Bot bot, String mode) {
        bot.clickSlot(slot(bot, mode));
        bot.clickSlot(slot(bot, "Yes, do it"));
    }

    /**
     * What the player carries, as the server counts it — the bot's own picture of its inventory waits while a window
     * is open. {@code clear} with a count of 0 counts and takes nothing.
     */
    private static int carried(PaperServer server, String player, String material) {
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("Found (\\d+)")
                .matcher(server.console("clear " + player + " minecraft:" + material + " 0"));
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    private static int slot(Bot bot, String label) {
        Supplier<Integer> found = () -> bot.window().flatMap(window -> window.slotNamed(label)).orElse(null);
        Await.until("a page with '" + label + "' is open for " + bot.name(), WAIT, () -> found.get() != null);
        return found.get();
    }

    private static List<String> errors(PaperServer server) {
        return server.errorsFrom("RainsOneBlock", "RainsCore", "RainsEconomy").stream()
                .filter(line -> !line.contains("resource pack server could not start")).toList();
    }
}
