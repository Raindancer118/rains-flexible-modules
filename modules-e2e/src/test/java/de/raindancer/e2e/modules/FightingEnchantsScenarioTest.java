package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fighting enchantments on a real server, struck by a real client: Overpower, Executioner, Berserk, Armor Pierce,
 * Lifesteal, Bleed, Venom and Cleave on mobs; totems put into an Undying chestplate and saving its wearer; two Fusion
 * chestplates fused in an anvil; and an anvil that would be too expensive asking 39 levels instead.
 */
@Tag("e2e")
class FightingEnchantsScenarioTest {

    private static final int Y = 64;
    private static int next = 1;

    /** A mob that stands still, with a UUID chosen here so the client can hit it. */
    private static UUID mob(Server server, String type, double x, double z, String extra) {
        int n = next++;
        server.console("summon minecraft:" + type + " " + x + " " + Y + " " + z + " {NoAI:1b,PersistenceRequired:1b,"
                + "Silent:1b,UUID:[I;7,7,7," + n + "]" + extra + "}");
        return new UUID((7L << 32) | 7L, (7L << 32) | n);
    }

    private static String sturdy(double armour) {
        return ",Health:200f,attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:armor\",base:" + armour
                + "},{id:\"minecraft:armor_toughness\",base:" + (armour > 0 ? 8 : 0) + "}]";
    }

    private static double health(Server server, UUID who) {
        Matcher found = Pattern.compile("data: ([0-9.]+)f").matcher(server.console("data get entity " + who + " Health"));
        return found.find() ? Double.parseDouble(found.group(1)) : -1;
    }

    private static double playerHealth(Server server) {
        Matcher found = Pattern.compile("data: ([0-9.]+)f").matcher(server.console("data get entity Fighter Health"));
        return found.find() ? Double.parseDouble(found.group(1)) : -1;
    }

    /** Fighter steps beside the mob with the weapon in hand, waits for a full swing, and hits it once. */
    private static double hit(Server server, Bot fighter, UUID target, double x, double z, String weapon) {
        server.console("item replace entity Fighter weapon.mainhand with " + weapon);
        server.console("tp Fighter " + x + " " + Y + " " + (z + 1.6) + " 180 0");
        // A swing the server did not take (the client had only just been moved) is swung again; the first that lands counts.
        for (int tries = 0; tries < 3; tries++) {
            Await.ticks(30);
            double before = health(server, target);
            fighter.attack(target);
            Await.ticks(6);
            double dealt = before - health(server, target);
            if (dealt > 0) {
                return dealt;
            }
        }
        return 0;
    }

    @Test
    @DisplayName("every fighting enchantment does what it says, totems keep and save, chestplates fuse, no anvil is too expensive")
    void fight() {
        try (Server server = Server.start("fighting-enchants", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            server.console("time set midnight");
            server.console("gamerule advance_time false");
            server.console("gamerule natural_health_regeneration false");
            // No wild skeleton walks up and shoots the Fighter while Berserk has them at a fifth of their health.
            server.console("gamerule spawn_mobs false");
            server.console("kill @e[type=!player]");
            server.console("forceload add -32 -32 32 32");
            server.console("fill -30 " + (Y - 1) + " -30 30 " + (Y - 1) + " 30 minecraft:stone");
            server.console("fill -30 " + Y + " -30 30 " + (Y + 4) + " 30 minecraft:air");
            Bot fighter = server.player("Fighter");
            server.console("gamemode survival Fighter");
            String sword = "netherite_sword";

            // --- Overpower: 1.5 more a level on top of the sword --------------------------------------------------
            double plain = hit(server, fighter, mob(server, "zombie", 0.5, 0.5, sturdy(0)), 0.5, 0.5, sword);
            assertThat(plain).as("a plain hit landed").isGreaterThan(5);
            double overpowered = hit(server, fighter, mob(server, "zombie", 6.5, 0.5, sturdy(0)), 6.5, 0.5,
                    sword + "[enchantments={\"rainsenchants:overpower\":5}]");
            assertThat(overpowered).as("Overpower V").isGreaterThan(plain + 6);

            // --- Executioner: a target below 30 % takes 45 % more ------------------------------------------------------
            UUID weak = mob(server, "zombie", 12.5, 0.5, sturdy(0));
            server.console("data merge entity " + weak + " {Health:40f}");
            double executed = hit(server, fighter, weak, 12.5, 0.5, sword + "[enchantments={\"rainsenchants:executioner\":3}]");
            assertThat(executed).as("Executioner III on one at 20 %").isGreaterThan(plain * 1.35);

            // --- Armor Pierce: through armour ------------------------------------------------------------------------------
            double armoured = hit(server, fighter, mob(server, "zombie", 18.5, 0.5, sturdy(20)), 18.5, 0.5, sword);
            double pierced = hit(server, fighter, mob(server, "zombie", 24.5, 0.5, sturdy(20)), 24.5, 0.5,
                    sword + "[enchantments={\"rainsenchants:armor_pierce\":3}]");
            assertThat(pierced).as("Armor Pierce III against 20 armour; plain did " + armoured).isGreaterThan(armoured * 1.5);

            // --- Berserk, then Lifesteal: hurt, a Berserk hit is harder; a Lifesteal hit heals ------------------------
            server.console("damage Fighter 16 minecraft:generic");
            Await.ticks(5);
            assertThat(playerHealth(server)).isLessThanOrEqualTo(5);
            double raging = hit(server, fighter, mob(server, "zombie", 0.5, 8.5, sturdy(0)), 0.5, 8.5,
                    sword + "[enchantments={\"rainsenchants:berserk\":3}]");
            assertThat(raging).as("Berserk III at a fifth of one's health").isGreaterThan(plain * 1.4);
            double hurt = playerHealth(server);
            double drained = hit(server, fighter, mob(server, "zombie", 6.5, 8.5, sturdy(0)), 6.5, 8.5,
                    sword + "[enchantments={\"rainsenchants:lifesteal\":3}]");
            assertThat(playerHealth(server) - hurt).as("Lifesteal III healed 14 % of the " + drained + " dealt")
                    .isCloseTo(drained * 0.14, org.assertj.core.api.Assertions.within(0.05));
            server.console("effect give Fighter minecraft:instant_health 1 10");

            // --- Bleed: hurts on after the hit -----------------------------------------------------------------------------
            UUID bleeding = mob(server, "zombie", 12.5, 8.5, sturdy(0));
            hit(server, fighter, bleeding, 12.5, 8.5, sword + "[enchantments={\"rainsenchants:bleed\":3}]");
            double afterHit = health(server, bleeding);
            Await.ticks(70);
            assertThat(health(server, bleeding)).as("still bleeding").isLessThan(afterHit - 0.9);

            // --- Venom: poisons ------------------------------------------------------------------------------------------------
            UUID poisoned = mob(server, "vindicator", 18.5, 8.5, sturdy(0));
            hit(server, fighter, poisoned, 18.5, 8.5, sword + "[enchantments={\"rainsenchants:venom\":3}]");
            for (String effect : new String[]{"poison", "wither"}) {
                assertThat(server.console("execute if data entity " + poisoned + " active_effects[{id:\"minecraft:"
                        + effect + "\"}]")).as(effect).contains("passed");
            }

            // --- Cleave: an axe strikes the ones beside its target too --------------------------------------------------
            UUID struck = mob(server, "vindicator", 0.5, 16.5, sturdy(0));
            UUID left = mob(server, "vindicator", -1.0, 16.0, sturdy(0));
            UUID right = mob(server, "vindicator", 2.0, 16.0, sturdy(0));
            hit(server, fighter, struck, 0.5, 16.5, "netherite_axe[enchantments={\"rainsenchants:cleave\":3}]");
            assertThat(health(server, left)).as("cleaved left").isLessThan(200);
            assertThat(health(server, right)).as("cleaved right").isLessThan(200);

            // --- Undying: totems clicked onto the chestplate go in, and one saves its wearer -----------------------------
            server.console("setblock 10 " + Y + " 20 minecraft:chest");
            server.console("item replace block 10 " + Y + " 20 container.0 with netherite_chestplate[enchantments={\"rainsenchants:undying\":1}]");
            server.console("clear Fighter");
            server.console("give Fighter totem_of_undying 3");
            server.console("tp Fighter 10.5 " + Y + " 22.5 180 30");
            Await.ticks(20);
            fighter.useOn(10, Y, 20);
            fighter.awaitWindow("container.chest");
            // Totems do not stack: one at a time onto the chestplate. The third finds it full and stays on the cursor.
            for (int hotbar = 0; hotbar < 3; hotbar++) {
                fighter.clickSlot(54 + hotbar);
                Await.ticks(5);
                fighter.clickSlot(0);
                Await.ticks(5);
            }
            fighter.clickSlot(54 + 2);
            Await.ticks(5);
            fighter.closeWindow();
            String kept = server.console("data get block 10 " + Y + " 20 Items[0].components");
            assertThat(kept).as("two went in, Undying I's room").contains("Totems kept: 2/2");
            Matcher left1 = Pattern.compile("Found (\\d+)").matcher(server.console("clear Fighter totem_of_undying 0"));
            assertThat(left1.find() ? Integer.parseInt(left1.group(1)) : 0).as("one left over").isEqualTo(1);
            server.console("clear Fighter");
            server.console("item replace entity Fighter armor.chest from block 10 " + Y + " 20 container.0");
            server.console("damage Fighter 500 minecraft:mob_attack");
            Await.ticks(20);
            assertThat(fighter.isDead()).as("saved by a kept totem").isFalse();
            assertThat(server.console("data get entity Fighter equipment.chest.components")).contains("Totems kept: 1/2");

            // --- Fusion and the anvil --------------------------------------------------------------------------------------
            server.console("clear Fighter");
            server.console("setblock 14 " + Y + " 20 minecraft:anvil");
            server.console("give Fighter iron_chestplate[enchantments={\"rainsenchants:fusion\":1}] 1");
            server.console("give Fighter iron_chestplate[enchantments={\"rainsenchants:fusion\":1}] 1");
            server.console("xp set Fighter 100 levels");
            server.console("tp Fighter 14.5 " + Y + " 22.5 180 30");
            Await.ticks(20);
            fighter.useOn(14, Y, 20);
            fighter.awaitWindow("container.repair");
            fighter.shiftClickSlot(30);
            Await.ticks(3);
            fighter.shiftClickSlot(31);
            Await.until("the anvil offers a fused chestplate", java.time.Duration.ofSeconds(10),
                    () -> fighter.window().map(window -> window.items().get(2)).map(item -> item.tags().toString()
                            .contains("fusions")).orElse(false));
            fighter.shiftClickSlot(2);
            Await.ticks(10);
            fighter.closeWindow();
            assertThat(server.console("data get entity Fighter Inventory[{id:\"minecraft:iron_chestplate\"}]"
                    + ".components.\"minecraft:custom_data\"")).as("fused once").contains("\"rainsenchants:fusions\": 1");
            assertThat(server.console("data get entity Fighter Inventory[{id:\"minecraft:iron_chestplate\"}]"
                    + ".components.\"minecraft:attribute_modifiers\"[{id:\"rainsenchants:fusion_armor\"}].amount"))
                    .as("an iron chestplate's 6 armour, doubled").contains("12.0d");

            // A combination that would cost more than 39 levels costs 39.
            server.console("clear Fighter");
            server.console("xp set Fighter 100 levels");
            server.console("give Fighter diamond_sword[repair_cost=60,enchantments={\"minecraft:sharpness\":4}] 1");
            server.console("give Fighter diamond_sword[repair_cost=60,enchantments={\"minecraft:sharpness\":4}] 1");
            Await.ticks(5);
            fighter.useOn(14, Y, 20);
            fighter.awaitWindow("container.repair");
            fighter.shiftClickSlot(30);
            Await.ticks(3);
            fighter.shiftClickSlot(31);
            Await.ticks(10);
            fighter.shiftClickSlot(2);
            Await.ticks(10);
            fighter.closeWindow();
            Matcher levels = Pattern.compile("has (\\d+) experience levels").matcher(server.console("xp query Fighter levels"));
            assertThat(levels.find() ? Integer.parseInt(levels.group(1)) : -1).as("charged 39, not refused").isEqualTo(61);
        }
    }
}
