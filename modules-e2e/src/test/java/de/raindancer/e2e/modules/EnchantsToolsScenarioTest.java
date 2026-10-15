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
 * The tool, armour and elytra enchantments on a real server, with the economy beside them for Auto Sell:
 * drops sold, smelted, picked up, sent to a linked chest; a face excavated, a field harvested and replanted;
 * gear kept through a death; effects while worn; and the pushes of Boost and Launch.
 */
@Tag("e2e")
class EnchantsToolsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int Y = 64;

    private static boolean has(Server server, String selector) {
        return server.console("execute if entity " + selector).contains("passed");
    }

    private static boolean done(Server server, String advancement) {
        return has(server, "@a[name=Tester,advancements={rainsenchants:" + advancement + "=true}]");
    }

    private static boolean block(Server server, int x, int y, int z, String what) {
        return server.console("execute if block " + x + " " + y + " " + z + " " + what).contains("passed");
    }

    /**
     * How many of an item Tester carries. Counted with {@code clear ... 0}, which takes nothing: printing the
     * inventory cuts it short after the first item.
     */
    private static int count(Server server, String item) {
        String said = server.console("clear Tester " + item + " 0");
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("Found (\\d+)").matcher(said);
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    /**
     * The amplifier of one of Tester's effects, -1 without it — asked one by one, as a list is printed cut short.
     * An amplifier of 0 is not written at all.
     */
    private static int amplifier(Server server, String effect) {
        String selector = "active_effects[{id:\"minecraft:" + effect + "\"}]";
        if (!server.console("execute if data entity Tester " + selector).contains("passed")) {
            return -1;
        }
        String said = server.console("data get entity Tester " + selector + ".amplifier");
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("data: (-?\\d+)b").matcher(said);
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    /** How many blocks of the 5x5 test field are {@code what}. */
    private static int field(Server server, String what) {
        int matching = 0;
        for (int x = 8; x <= 12; x++) {
            for (int z = 8; z <= 12; z++) {
                matching += block(server, x, Y, z, what) ? 1 : 0;
            }
        }
        return matching;
    }

    private static void hand(Server server, String item) {
        server.console("item replace entity Tester weapon.mainhand with " + item);
        Await.ticks(5);
    }

    private static long balance(Bot bot) {
        bot.forgetChat();
        bot.run("balance");
        String line = Await.value(() -> "the balance line", WAIT, () -> bot.chatText().stream()
                .filter(text -> text.contains("You have")).findFirst().orElse(null));
        return Long.parseLong(line.substring(line.indexOf('⛃') + 1).split("[^0-9,]")[0].replace(",", ""));
    }

    /** The strongest push, in blocks a tick, that arrived after the first {@code skip}. */
    private static double longestPush(Bot bot, int skip) {
        return bot.pushes().stream().skip(skip).mapToDouble(p -> Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]))
                .max().orElse(0);
    }

    @Test
    @DisplayName("auto sell, smelting, telekinesis, chest link, excavator, harvester, soulbound, effects, boost and launch")
    void tools() {
        try (Server server = Server.start("enchants-tools",
                List.of("enchants-standalone:RainsEnchants-.*", "economy-standalone:RainsEconomy-.*",
                        "essentials-standalone:RainsEssentials-.*"),
                List.of("Enchants are up", "The economy is up"))) {
            server.console("forceload add -16 -16 32 32");
            server.console("fill -8 " + (Y - 4) + " -8 24 " + (Y + 20) + " 24 minecraft:air");
            server.console("fill -8 " + (Y - 4) + " -8 24 " + (Y - 1) + " 24 minecraft:stone");

            Bot tester = server.player("Tester");
            server.console("gamemode survival Tester");
            server.console("clear Tester");
            server.console("tp Tester 0.5 " + Y + " 0.5 0 0");
            Await.ticks(20);
            String pick = "minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":10";

            // --- our books are not in the shop: they are handed out by whoever gives them ---------------------
            assertThat(server.console("eco price enchanted_book:rainsenchants:flight:1")).doesNotContain("Buy ⛃");

            // --- Auto Sell: the cobblestone never lands, the money arrives ------------------------------------
            long before = balance(tester);
            hand(server, pick + ",\"rainsenchants:auto_sell\":1}]");
            tester.dig(0, Y - 1, 2);
            Await.until("the stone breaks", WAIT, () -> block(server, 0, Y - 1, 2, "minecraft:air"));
            Await.until("the sale is paid", WAIT, () -> balance(tester) > before);
            assertThat(count(server, "minecraft:cobblestone")).isZero();
            assertThat(server.console("execute if entity @e[type=item]")).doesNotContain("passed");
            Await.until("its advancement", WAIT, () -> done(server, "auto_sell"));

            // --- Smelting with Telekinesis: an ingot straight into the inventory ------------------------------
            // Telekinesis is put on with Essentials' /enchant, by its bare name, as staff would.
            server.console("setblock 1 " + (Y - 1) + " 2 minecraft:iron_ore");
            hand(server, pick + ",\"rainsenchants:smelting\":1}]");
            String enchanted = server.console("enchant telekinesis 1 Tester");
            assertThat(enchanted).doesNotContain("no enchantment");
            // Asked by path: a whole inventory is printed with its nested parts cut short to "...".
            Await.until("/enchant put Telekinesis on", WAIT, () -> server.console(
                    "data get entity Tester Inventory[0].components.\"minecraft:enchantments\"")
                    .contains("rainsenchants:telekinesis"));
            tester.dig(1, Y - 1, 2);
            Await.until("the ore breaks", WAIT, () -> block(server, 1, Y - 1, 2, "minecraft:air"));
            Await.until("the ingot is carried", WAIT, () -> count(server, "minecraft:iron_ingot") == 1);
            assertThat(count(server, "minecraft:raw_iron")).isZero();
            Await.until("both advancements", WAIT, () -> done(server, "smelting") && done(server, "telekinesis"));
            server.console("clear Tester");

            // --- Excavator: a 3x3 wall in front comes out at once ---------------------------------------------
            server.console("fill -1 " + Y + " 6 1 " + (Y + 2) + " 6 minecraft:stone");
            server.console("tp Tester 0.5 " + Y + " 4.5 0 0");
            Await.ticks(10);
            hand(server, pick + ",\"rainsenchants:excavator\":1}]");
            tester.dig(0, Y + 1, 6);
            Await.until("the whole face is gone", WAIT, () -> {
                for (int x = -1; x <= 1; x++) {
                    for (int y = Y; y <= Y + 2; y++) {
                        if (!block(server, x, y, 6, "minecraft:air")) {
                            return false;
                        }
                    }
                }
                return true;
            });
            Await.until("its advancement", WAIT, () -> done(server, "excavator"));
            server.console("kill @e[type=item]");

            // --- Harvester III: a 5x5 field in one swing, every crop planted again ---------------------------
            server.console("fill 8 " + (Y - 1) + " 8 12 " + (Y - 1) + " 12 minecraft:farmland");
            server.console("fill 8 " + Y + " 8 12 " + Y + " 12 minecraft:wheat[age=7]");
            server.console("tp Tester 10.5 " + Y + " 6.5 0 30");
            Await.ticks(10);
            hand(server, "minecraft:netherite_hoe[minecraft:enchantments={\"rainsenchants:harvester\":3,"
                    + "\"rainsenchants:telekinesis\":1}]");
            tester.dig(10, Y, 10);
            try {
                Await.until("all 25 are replanted", WAIT, () -> field(server, "minecraft:wheat[age=0]") == 25);
            } catch (AssertionError timedOut) {
                throw new AssertionError(timedOut.getMessage() + " — seedlings " + field(server, "minecraft:wheat[age=0]")
                        + ", ripe " + field(server, "minecraft:wheat[age=7]") + ", empty " + field(server, "minecraft:air"),
                        timedOut);
            }
            assertThat(count(server, "minecraft:wheat")).isPositive();
            Await.until("a bumper crop", WAIT, () -> done(server, "bumper_crop"));
            server.console("clear Tester");

            // --- Chest Link: sneak-click a chest, then what is mined lands in it ----------------------------
            server.console("setblock 3 " + Y + " 0 minecraft:chest");
            server.console("tp Tester 0.5 " + Y + " 0.5 0 0");
            Await.ticks(10);
            hand(server, pick + ",\"rainsenchants:chest_link\":1}]");
            tester.forgetChat();
            tester.sneak(true);
            tester.useOn(3, Y, 0);
            Await.until("the chest is linked", WAIT, () -> tester.chatText().stream().anyMatch(l -> l.contains("Linked to the chest")));
            tester.sneak(false);
            tester.dig(-1, Y - 1, 1);
            Await.until("the cobblestone is in the chest", WAIT, () -> server.console("data get block 3 " + Y + " 0 Items")
                    .contains("cobblestone"));
            Await.until("its advancement", WAIT, () -> done(server, "chest_link"));
            server.console("clear Tester");

            // --- Haste in hand, Swiftness on the legs -------------------------------------------------------
            hand(server, "minecraft:iron_pickaxe[minecraft:enchantments={\"rainsenchants:haste\":2}]");
            server.console("item replace entity Tester armor.legs with minecraft:iron_leggings"
                    + "[minecraft:enchantments={\"rainsenchants:swiftness\":1}]");
            Await.until("haste II and speed I", WAIT,
                    () -> amplifier(server, "haste") == 1 && amplifier(server, "speed") == 0);
            hand(server, "minecraft:air");
            Await.until("haste goes with the tool", WAIT, () -> amplifier(server, "haste") < 0);
            server.console("clear Tester");
            server.console("effect clear Tester");

            // --- Soulbound: kept through a death, a level poorer ---------------------------------------------
            hand(server, "minecraft:diamond_sword[minecraft:enchantments={\"rainsenchants:soulbound\":2}]");
            server.console("kill Tester");
            Await.until("Tester died", WAIT, tester::isDead);
            tester.respawn();
            Await.until("the sword came back", WAIT, () -> count(server, "minecraft:diamond_sword") == 1);
            assertThat(server.console("data get entity Tester Inventory[0].components.\"minecraft:enchantments\""))
                    .contains("\"rainsenchants:soulbound\": 1");
            assertThat(server.console("execute if entity @e[type=item,nbt={Item:{id:\"minecraft:diamond_sword\"}}]"))
                    .as("not dropped as well").doesNotContain("passed");
            Await.until("its advancement", WAIT, () -> done(server, "soulbound"));
            server.console("clear Tester");

            // --- Boost: mid-glide, a click pulls harder than a rocket ---------------------------------------
            server.console("tp Tester 0.5 " + (Y + 15) + " 0.5 0 0");
            server.console("item replace entity Tester armor.chest with minecraft:elytra"
                    + "[minecraft:enchantments={\"rainsenchants:boost\":2}]");
            hand(server, "minecraft:stick");
            Await.ticks(10);
            tester.moveTo(0.5, Y + 14.5, 0.5, false);
            tester.startGliding();
            Await.until("Tester glides", WAIT, () -> has(server, "@a[name=Tester,nbt={FallFlying:1b}]"));
            int beforeBoost = tester.pushes().size();
            tester.forgetEffects();
            tester.useHeld();
            Await.until("a boost faster than a rocket's 1.5", WAIT, () -> longestPush(tester, beforeBoost) > 1.5);
            Await.until("its advancement", WAIT, () -> done(server, "boost"));
            Await.until("a sonic boom, like Launch's", WAIT, () -> tester.particlesSeen().contains("SONIC_BOOM"));
            server.console("item replace entity Tester armor.chest with minecraft:air");
            server.console("tp Tester 0.5 " + Y + " 0.5 0 0");
            Await.ticks(40);

            // --- Launch: sneak and jump, up, the elytra opens, and away at 200 m/s ---------------------------
            server.console("item replace entity Tester armor.chest with minecraft:elytra"
                    + "[minecraft:enchantments={\"rainsenchants:launch\":1}]");
            Await.ticks(10);
            int beforeLaunch = tester.pushes().size();
            tester.sneak(true);
            tester.moveTo(0.5, Y + 0.42, 0.5, false);
            Await.until("thrown up", WAIT, () -> tester.pushes().stream().skip(beforeLaunch).anyMatch(p -> p[1] > 2));
            Await.until("flung forward at ten blocks a tick", WAIT, () -> longestPush(tester, beforeLaunch) > 9);
            Await.until("its advancement", WAIT, () -> done(server, "launch"));
            tester.sneak(false);

            // --- Launch III: no cooldown, so it launches again straight away ---------------------------------------
            server.console("item replace entity Tester armor.chest with minecraft:elytra"
                    + "[minecraft:enchantments={\"rainsenchants:launch\":3}]");
            for (int launch = 1; launch <= 2; launch++) {
                server.console("tp Tester 0.5 " + Y + " 0.5");
                tester.moveTo(0.5, Y, 0.5, true);
                Await.ticks(20);
                int pushesBefore = tester.pushes().size();
                tester.sneak(true);
                tester.moveTo(0.5, Y + 0.42, 0.5, false);
                int which = launch;
                Await.until("launch " + which + " of Launch III", WAIT,
                        () -> tester.pushes().stream().skip(pushesBefore).anyMatch(p -> p[1] > 2));
                tester.sneak(false);
                Await.ticks(10);
            }

            // Known and not this scenario's: Essentials 1.27.0's network welcome reads and writes its document on
            // the server thread at join (EssentialsScenarioTest fails on it). Only those two lines are let through.
            assertThat(server.paper.errorsFrom("RainsCore", "RainsEnchants", "RainsEconomy", "RainsEssentials").stream()
                    .filter(line -> !line.contains("Documents.read:") && !line.contains("Documents.insertIfAbsent:")))
                    .isEmpty();
        }
    }
}
