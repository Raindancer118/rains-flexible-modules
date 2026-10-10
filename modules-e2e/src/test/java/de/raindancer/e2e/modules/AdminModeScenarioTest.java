package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin mode on a real server: containers can be used, and nothing done there is an advancement. */
@Tag("e2e")
class AdminModeScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int Y = 71;

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static boolean has(Server server, String advancement) {
        return server.console("execute if entity @a[name=Ada,advancements={" + advancement + "=true}]").contains("passed");
    }

    @Test
    @DisplayName("in admin mode a chest takes what is put in it, and an advancement is not made — out of it, it is, and pays")
    void adminMode() {
        try (Server server = Server.start("adminmode", List.of("essentials-standalone:RainsEssentials-.*",
                "economy-standalone:RainsEconomy-.*"), List.of("Essentials are up", "The economy is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -6 " + (Y - 3) + " -6 6 " + (Y - 1) + " 6 minecraft:stone");
            server.console("fill -6 " + Y + " -6 6 " + (Y + 3) + " 6 minecraft:air");
            server.console("setblock 2 " + Y + " 0 minecraft:chest");
            Bot ada = server.admin("Ada");
            server.console("tp Ada 0.5 " + Y + " 0.5");
            Await.ticks(20);
            ada.forgetChat();
            ada.run("admin on");
            ada.expectChat("Admin mode on");

            // ---- a chest: open it, put diamonds in
            server.console("clear Ada");
            server.console("give Ada minecraft:diamond 5");
            Await.ticks(10);
            ada.useOn(2, Y, 0);
            ada.awaitWindow("container.chest");
            Await.ticks(10);
            int diamonds = Await.value("the diamonds in Ada's hotbar, below the chest", WAIT, () -> {
                var window = ada.window().orElse(null);
                if (window == null) {
                    return null;
                }
                return window.items().entrySet().stream()
                        .filter(entry -> entry.getKey() >= window.size() && entry.getValue().is("diamond"))
                        .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
            });
            ada.shiftClickSlot(diamonds);
            Await.ticks(10);
            ada.closeWindow();
            Await.until("the diamonds are in the chest", WAIT, () -> server.console(
                    "execute if items block 2 " + Y + " 0 container.* minecraft:diamond").contains("passed"));

            // ---- no advancement in admin mode
            server.console("gamemode survival Ada");
            server.console("advancement grant Ada only minecraft:story/mine_stone");
            Await.ticks(20);
            assertThat(has(server, "minecraft:story/mine_stone")).as("made in admin mode").isFalse();

            // ---- out of admin mode it is made, and paid
            ada.forgetChat();
            ada.run("admin off");
            ada.expectChat("Admin mode off");
            server.console("gamemode survival Ada");
            ada.forgetChat();
            server.console("advancement grant Ada only minecraft:story/mine_stone");
            Await.until("made out of admin mode", WAIT, () -> has(server, "minecraft:story/mine_stone"));
            Await.until(() -> "and paid (heard " + ada.chatText() + ")", WAIT,
                    () -> said(ada, "for the advancement Stone Age"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsEconomy")).isEmpty();
        }
    }
}
