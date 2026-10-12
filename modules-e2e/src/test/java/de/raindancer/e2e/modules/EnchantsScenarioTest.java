package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Downloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rain's enchantments on a real server: they exist in the registry, Night Vision shows and goes with the
 * helmet, Flight allows flying until its charge is gone, Double Jump pushes up in mid-air and runs out,
 * Veinminer only works with the enchanted tool, and the advancements are granted.
 */
@Tag("e2e")
class EnchantsScenarioTest {

    private static final String VEINMINER_URL =
            "https://cdn.modrinth.com/data/OhduvhIc/versions/O12m3jOR/veinminer-paper-2.12.3.jar";
    private static final String VEINMINER_SHA1 = "d40cdbd279781b0045b65156abd8079b77484634";
    private static final int Y = 64;
    private static final int FIRST = 3;
    private static final int LAST = 7;

    private static boolean has(Server server, String selector) {
        return server.console("execute if entity " + selector).contains("passed");
    }

    private static boolean done(Server server, String advancement) {
        return has(server, "@a[name=Tester,advancements={rainsenchants:" + advancement + "=true}]");
    }

    private static boolean effect(Server server, String effect) {
        return server.console("data get entity Tester active_effects").contains("minecraft:" + effect);
    }

    private static boolean flag(Server server, String ability) {
        return server.console("data get entity Tester abilities." + ability).contains("1b");
    }

    private static int veinBlocksLeft(Server server) {
        int left = 0;
        for (int x = FIRST; x <= LAST; x++) {
            if (server.console("execute if block " + x + " " + Y + " 5 minecraft:diamond_ore").contains("passed")) {
                left++;
            }
        }
        return left;
    }

    @Test
    @DisplayName("the four enchantments work on a real server, and veins need the enchanted tool")
    void enchantments() {
        Path veinminer = Downloads.pinned("veinminer-paper-2.12.3.jar", VEINMINER_URL, VEINMINER_SHA1);
        try (Server server = Server.start("enchants",
                List.of("enchants-standalone:RainsEnchants-.*", "veintoggle-standalone:RainsVeinToggle-.*"),
                List.of(veinminer), List.of("Enchants are up", "Vein toggle is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -8 " + (Y - 4) + " -8 16 " + (Y + 12) + " 16 minecraft:air");
            server.console("fill -8 " + (Y - 4) + " -8 16 " + (Y - 1) + " 16 minecraft:stone");
            server.console("fill 0 " + Y + " 5 2 " + (Y + 1) + " 5 minecraft:air");

            Bot tester = server.player("Tester");
            server.console("gamemode survival Tester");
            server.console("clear Tester");
            server.console("tp Tester 0.5 " + Y + " 0.5");
            Await.ticks(20);

            // --- registry: a vanilla command accepts the id, so the enchantment exists --------------------
            assertThat(server.console("give Tester minecraft:book[minecraft:enchantments={\"rainsenchants:flight\":3}]"))
                    .doesNotContain("Unknown").doesNotContain("Invalid");
            server.console("clear Tester");

            // --- /enchants give hands out a real book ------------------------------------------------------
            String given = server.console("enchants give Tester flight 3");
            assertThat(given).contains("Gave Tester a book of flight 3");
            assertThat(server.console("data get entity Tester Inventory[0].components.\"minecraft:stored_enchantments\""))
                    .contains("rainsenchants:flight").contains("3");
            server.console("clear Tester");

            // --- Night Vision -----------------------------------------------------------------------------
            server.console("item replace entity Tester armor.head with minecraft:iron_helmet"
                    + "[minecraft:enchantments={\"rainsenchants:night_vision\":1}]");
            Await.until("night vision shows", Duration.ofSeconds(10), () -> effect(server, "night_vision"));
            Await.until("its advancement is granted", Duration.ofSeconds(5), () -> done(server, "night_vision"));
            server.console("item replace entity Tester armor.head with minecraft:air");
            Await.until("night vision goes with the helmet", Duration.ofSeconds(10),
                    () -> !effect(server, "night_vision"));

            // --- Double Jump: three mid-air jumps, then none ----------------------------------------------
            server.console("item replace entity Tester armor.feet with minecraft:iron_boots"
                    + "[minecraft:enchantments={\"rainsenchants:double_jump\":3}]");
            Await.until("flight is allowed for the jump", Duration.ofSeconds(10), () -> flag(server, "mayfly"));
            // Held in the air: the headless client has no gravity of its own, and a jump counts as used
            // until the player has landed.
            server.console("tp Tester 0.5 " + (Y + 8) + " 0.5");
            Await.ticks(10);
            // The headless client does not obey velocity packets, so the push itself cannot be seen here: what
            // is proved is that the toggle was taken as a jump (not flight) and counted.
            tester.flying(true);
            Await.until("the first jump is counted", Duration.ofSeconds(5), () -> done(server, "double_jump"));
            assertThat(flag(server, "flying")).as("a jump is not flying").isFalse();
            for (int jump = 2; jump <= 3; jump++) {
                Await.until("flight is offered again for jump " + jump, Duration.ofSeconds(5),
                        () -> flag(server, "mayfly"));
                tester.flying(true);
                Await.ticks(4);
            }
            Await.until("three jumps in a row earn the triple threat", Duration.ofSeconds(5),
                    () -> done(server, "triple_jump"));
            Await.until("with the jumps used up there is nothing to toggle", Duration.ofSeconds(5),
                    () -> !flag(server, "mayfly"));
            server.console("item replace entity Tester armor.feet with minecraft:air");
            server.console("tp Tester 0.5 " + Y + " 0.5");
            Await.ticks(40);

            // --- Flight: allowed, drains, ends with a soft landing ----------------------------------------
            server.console("item replace entity Tester armor.chest with minecraft:iron_chestplate"
                    + "[minecraft:enchantments={\"rainsenchants:flight\":1}]");
            Await.until("flight is allowed", Duration.ofSeconds(10), () -> flag(server, "mayfly"));
            tester.flying(true);
            Await.until("the player flies", Duration.ofSeconds(5), () -> flag(server, "flying"));
            Await.until("its advancement is granted", Duration.ofSeconds(5), () -> done(server, "flight"));
            Await.until("the charge runs out and flight is taken away", Duration.ofSeconds(60),
                    () -> !flag(server, "flying") && !flag(server, "mayfly"));
            assertThat(effect(server, "slow_falling")).as("running dry is survivable").isTrue();
            Await.until("running on empty is noticed", Duration.ofSeconds(5), () -> done(server, "empty_tank"));
            server.console("item replace entity Tester armor.chest with minecraft:air");
            server.console("effect clear Tester");

            // --- Veinminer: no enchantment, no vein -------------------------------------------------------
            server.console("fill " + FIRST + " " + Y + " 5 " + LAST + " " + Y + " 5 minecraft:diamond_ore");
            server.console("item replace entity Tester weapon.mainhand with minecraft:netherite_pickaxe"
                    + "[minecraft:enchantments={\"minecraft:efficiency\":10}]");
            server.console("tp Tester 2.5 " + Y + " 5.5 -90 0");
            Await.ticks(20);
            tester.dig(FIRST, Y, 5);
            Await.until("the first block breaks", Duration.ofSeconds(10), () -> veinBlocksLeft(server) < LAST - FIRST + 1);
            Await.ticks(40);
            assertThat(veinBlocksLeft(server)).as("an ordinary pickaxe breaks one block").isEqualTo(LAST - FIRST);

            server.console("item replace entity Tester weapon.mainhand with minecraft:netherite_pickaxe"
                    + "[minecraft:enchantments={\"minecraft:efficiency\":10,\"rainsenchants:veinminer\":1}]");
            tester.dig(FIRST + 1, Y, 5);
            Await.until("the enchanted pickaxe takes the rest of the vein", Duration.ofSeconds(15),
                    () -> veinBlocksLeft(server) == 0);
            Await.until("its advancement is granted", Duration.ofSeconds(5), () -> done(server, "veinminer"));
            Await.until("the tab opens with the first one", Duration.ofSeconds(5), () -> done(server, "root"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEnchants", "RainsVeinToggle")).isEmpty();
        }
    }
}
