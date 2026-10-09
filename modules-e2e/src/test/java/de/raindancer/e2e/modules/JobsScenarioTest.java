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

/** The job board on a real server: a delivery goal, the shop holding back what it collects, shares paid. */
@Tag("e2e")
class JobsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String COD_GOAL = "Cod for the harbour";

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static boolean lore(Bot bot, String button, String text) {
        return bot.window().flatMap(window -> window.slotNamed(button).map(slot -> window.top().get(slot)))
                .map(item -> item.lore().stream().anyMatch(line -> line.contains(text))).orElse(false);
    }

    /** Opens the board and hands in, retrying when the harness loses the click into a fresh window. */
    private static void handIn(Bot bot, String expect) {
        bot.forgetChat();
        for (int attempt = 0; attempt < 3 && !said(bot, expect); attempt++) {
            bot.run("jobs");
            bot.awaitWindow("Job board");
            Await.until("the cod goal is on the board", WAIT, () -> lore(bot, COD_GOAL, "Click to hand in"));
            Await.ticks(10);
            bot.clickSlot(bot.window().orElseThrow().slotNamed(COD_GOAL).orElseThrow());
            Await.ticks(40);
        }
        Await.until(() -> bot.name() + " hands in (heard " + bot.chatText() + ")", WAIT, () -> said(bot, expect));
        bot.closeWindow();
    }

    @Test
    @DisplayName("cod handed in by two players, the shop selling none meanwhile, both paid their share when it ends")
    void jobs() {
        try (Server server = Server.start("jobs", List.of("economy-standalone:RainsEconomy-.*",
                "jobs-standalone:RainsJobs-.*"), List.of("The economy is up", "The job board is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Await.ticks(20);
            server.console("jobs new cod");
            Await.ticks(10);
            String list = server.console("jobs list");
            Matcher number = Pattern.compile("#(\\d+) " + COD_GOAL).matcher(list);
            assertThat(number.find()).as("the cod goal is up: %s", list).isTrue();

            // ---- the board shows it; the shop does not sell cod while it is collected
            bo.run("shop cod");
            bo.awaitWindow("Cod");
            Await.until("the shop holds cod back", WAIT, () -> lore(bo, "Cod", "Not sold now"));
            bo.closeWindow();

            // ---- 30 from Bo, 20 from Ada (Ada keeps one renamed cod: it does not count)
            server.console("give Bo minecraft:cod 30");
            server.console("give Ada minecraft:cod 20");
            server.console("give Ada minecraft:cod[minecraft:custom_name='\"Lucky\"'] 1");
            Await.ticks(20);
            handIn(bo, "Handed in 30 for " + COD_GOAL);
            handIn(ada, "Handed in 20 for " + COD_GOAL);
            assertThat(ada.carrying(item -> item.is("cod"))).as("the renamed cod stays").isPresent();

            // ---- staff end it: 50 of 150 reached, a third of the reward (the median, 1,000) shared 60/40
            bo.forgetChat();
            ada.forgetChat();
            server.console("jobs end " + number.group(1));
            Await.until(() -> "Bo is paid his share (heard " + bo.chatText() + ")", WAIT,
                    () -> said(bo, "For your part in " + COD_GOAL) && said(bo, "60%") && said(bo, "⛃200"));
            Await.until("Ada is paid hers", WAIT, () -> said(ada, "40%") && said(ada, "⛃133"));
            bo.forgetChat();
            bo.run("balance");
            Await.until("Bo has 1,200", WAIT, () -> said(bo, "⛃1,200"));

            // ---- the board refilled, and cod is for sale again
            String after = server.console("jobs list");
            assertThat(after).doesNotContain("#" + number.group(1) + " ");
            bo.run("shop cod");
            bo.awaitWindow("Cod");
            Await.until(() -> "cod is sold again (board: " + after + "; window: " + bo.window()
                    .map(window -> window.top().toString()).orElse("none") + ")", WAIT, () -> lore(bo, "Cod", "Buy one:"));
            bo.closeWindow();
        }
    }
}
