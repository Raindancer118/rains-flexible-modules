package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's vault on a real server: the Banhammer put away with a sneaking right click, items
 * shift-clicked in, armour onto its stand and back on, and everything taken out again.
 */
@Tag("e2e")
class VaultScenarioTest {

    private static final String BANHAMMER =
            "minecraft:mace[minecraft:custom_name=[{text:'Ban',color:'red'},{text:'hammer',color:'gold'}]]";

    /** Hotbar slot n of the player, as numbered in a six-row chest window. */
    private static int hotbarInChest(int n) {
        return 81 + n;
    }

    private static boolean topHas(Bot bot, String material) {
        return bot.window().map(window -> window.top().values().stream().anyMatch(item -> item.is(material)))
                .orElse(false);
    }

    @Test
    @DisplayName("sneak-right-click stores the Banhammer; /vault takes items and armour in and out; ops only")
    void vault() {
        try (Server server = Server.start("vault",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // Not an operator: no vault.
            bo.run("vault");
            Await.ticks(20);
            assertThat(bo.window()).as("Bo has no vault").isEmpty();

            // Right-clicking without sneaking leaves the hammer where it is.
            server.console("item replace entity Ada hotbar.0 with " + BANHAMMER);
            ada.hold(0);
            Await.ticks(5);
            ada.useHeld();
            Await.ticks(10);
            assertThat(ada.hotbar().get(0)).as("not sneaking: still in hand").isNotNull();

            // Sneaking, it goes into the vault.
            ada.forgetChat();
            ada.sneak(true);
            ada.useHeld();
            Await.until("the hammer leaves Ada's hand", Duration.ofSeconds(10), () -> ada.hotbar().get(0) == null);
            ada.sneak(false);
            Await.until("Ada is told", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("Banhammer is in your vault")));

            server.console("item replace entity Ada hotbar.1 with minecraft:dirt 64");
            server.console("item replace entity Ada hotbar.2 with minecraft:diamond_helmet");
            Await.ticks(5);

            ada.run("vault");
            ada.awaitWindow("Vault");
            Await.until("the hammer is in the vault", Duration.ofSeconds(10), () -> topHas(ada, "mace"));

            ada.shiftClickSlot(hotbarInChest(1));
            Await.until("the dirt goes in", Duration.ofSeconds(10), () -> topHas(ada, "dirt"));
            assertThat(ada.hotbar().get(1)).as("and leaves Ada").isNull();

            ada.shiftClickSlot(hotbarInChest(2));
            Await.until("the helmet goes onto its stand", Duration.ofSeconds(10), () -> topHas(ada, "diamond_helmet"));

            ada.click("Equip all");
            Await.until("Ada wears the helmet", Duration.ofSeconds(10),
                    () -> ada.inventory().get(5) != null && ada.inventory().get(5).is("diamond_helmet"));

            ada.click("Take all");
            Await.until("everything is back in Ada's inventory", Duration.ofSeconds(10),
                    () -> ada.carrying(item -> item.is("mace")).isPresent()
                            && ada.carrying(item -> item.is("dirt")).isPresent());
            Await.until("the page is empty again", Duration.ofSeconds(10), () -> !topHas(ada, "mace"));
            ada.closeWindow();
            Await.ticks(10);
            assertThat(ada.items().stream().filter(item -> item.is("mace")).count())
                    .as("exactly one hammer, never a copy").isEqualTo(1);
            assertThat(ada.items().stream().filter(item -> item.is("dirt")).mapToInt(Bot.Item::amount).sum())
                    .isEqualTo(64);

            // And a vault that was put away is still there next time.
            server.console("item replace entity Ada hotbar.3 with minecraft:emerald 5");
            ada.run("vault");
            ada.awaitWindow("Vault");
            ada.shiftClickSlot(hotbarInChest(3));
            Await.until("the emeralds go in", Duration.ofSeconds(10), () -> topHas(ada, "emerald"));
            ada.closeWindow();
            ada.run("vault");
            ada.awaitWindow("Vault");
            Await.until("they are still there on reopening", Duration.ofSeconds(10), () -> topHas(ada, "emerald"));
            ada.closeWindow();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
