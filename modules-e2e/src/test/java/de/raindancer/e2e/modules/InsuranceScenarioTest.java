package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Inventory snapshots with insurance on a real server: it starts, /insurance opens, an item can be insured. */
@Tag("e2e")
class InsuranceScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("the module starts, /insurance opens, and an item in hand can be insured for its premium")
    void insurance() {
        try (Server server = Server.start("insurance", List.of("economy-standalone:RainsEconomy-.*",
                "invsnap-standalone:RainsInventorySnapshots-.*"), List.of("The economy is up",
                "Inventory snapshots are up"))) {
            Bot bo = server.player("Bo");
            assertThat(server.console("settings set invsnap:item-insurance.enabled true")).contains("is now");
            assertThat(server.console("settings set invsnap:item-insurance.price-flat 25")).contains("is now");
            Await.ticks(20);
            bo.runAndOpen("insurance", "Insu");
            bo.closeWindow();
            server.console("give Bo minecraft:diamond_sword 1");
            Await.until("the sword arrives", WAIT, () -> bo.carrying(item -> item.is("diamond_sword")).isPresent());
            bo.hold(bo.hotbarSlotOf(item -> item.is("diamond_sword")));
            bo.forgetChat();
            bo.run("insurance item");
            Await.ticks(20);
            bo.window().ifPresent(window -> {
                int yes = window.top().entrySet().stream().filter(entry -> entry.getValue().name().contains("Yes"))
                        .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                bo.clickSlot(yes);
            });
            bo.forgetChat();
            bo.run("balance");
            Await.until("the premium left his account", WAIT, () -> said(bo, "You have ⛃975"));
            assertThat(server.paper.errorsFrom("RainsCore", "RainsInventorySnapshots")).isEmpty();
        }
    }
}
