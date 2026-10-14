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
 * The way to Rain's enchantments on a real server: the shard world exists in eternal night, an obsidian frame
 * opened with an amethyst shard leads there and any portal there leads back; shards sell to the shop; a guild
 * pays them in and unlocks an enchantment; its members buy the books with shards and money, locked ones refused.
 */
@Tag("e2e")
class ShardsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final int Y = 64;

    private static int count(Server server, String item) {
        String answer = server.console("clear Ada minecraft:" + item + " 0");
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("Found (\\d+)").matcher(answer);
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    private static boolean told(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static long balance(Bot bot) {
        bot.forgetChat();
        bot.run("balance");
        String line = Await.value(() -> "the balance line", WAIT, () -> bot.chatText().stream()
                .filter(text -> text.contains("You have")).findFirst().orElse(null));
        return Long.parseLong(line.substring(line.indexOf('⛃') + 1).split("[^0-9,]")[0].replace(",", ""));
    }

    /** An obsidian frame along x with a 2x3 inside starting at (x, y, z). */
    private static void frame(Server server, String world, int x, int y, int z) {
        String in = "execute in " + world + " run ";
        server.console(in + "fill " + (x - 1) + " " + (y - 1) + " " + z + " " + (x + 2) + " " + (y + 3) + " " + z
                + " minecraft:obsidian");
        server.console(in + "fill " + x + " " + y + " " + z + " " + (x + 1) + " " + (y + 2) + " " + z + " minecraft:air");
    }

    @Test
    @DisplayName("shard world, portals both ways, shards sold, a guild unlocking, books bought")
    void shards() {
        try (Server server = Server.start("shards",
                List.of("enchants-standalone:RainsEnchants-.*", "economy-standalone:RainsEconomy-.*",
                        "guilds-standalone:RainsGuilds-.*"), List.of(),
                java.util.Map.of("plugins/RainsEnchants/config.yml", "shard-drop-percent: 100\n"),
                List.of("Enchants are up", "The economy is up", "Guilds are up"))) {
            // --- the shard world: there, and always midnight -----------------------------------------------
            // 26.x: time is a timeline per world; the overworld's must keep running.
            assertThat(server.console("execute in minecraft:shardlands run time query day")).contains("at 18000");
            Await.ticks(40);
            assertThat(server.console("execute in minecraft:shardlands run time query day"))
                    .as("the clock stands still").contains("at 18000");
            assertThat(server.console("execute in minecraft:overworld run gamerule advance_time"))
                    .as("only there").contains("true");

            Bot ada = server.player("Ada");
            server.console("gamemode survival Ada");
            server.console("forceload add -16 -16 32 32");
            server.console("fill -8 " + (Y - 4) + " -8 24 " + (Y + 8) + " 24 minecraft:air");
            server.console("fill -8 " + (Y - 4) + " -8 24 " + (Y - 2) + " 24 minecraft:stone");
            server.console("tp Ada 2.5 " + (Y - 1) + " 2.5");
            Await.ticks(20);

            // --- an obsidian frame opened with an amethyst shard ---------------------------------------------
            frame(server, "minecraft:overworld", 4, Y - 1, 4);
            server.console("item replace entity Ada weapon.mainhand with minecraft:amethyst_shard 2");
            Await.ticks(5);
            ada.useOn(4, Y - 2, 4);
            Await.until("the portal opens", WAIT, () -> server.console("execute if block 4 " + (Y - 1)
                    + " 4 minecraft:nether_portal").contains("passed"));
            assertThat(count(server, "amethyst_shard")).as("the key is used up").isEqualTo(1);

            // --- through it, into the shard world, and back through any portal there ----------------------
            server.console("tp Ada 4.5 " + (Y - 1) + " 4.5");
            Await.until("Ada is in the shard world", Duration.ofSeconds(30), () -> ada.world().contains("shardlands"));
            Await.until("its advancement", WAIT, () -> server.console(
                    "execute if entity @a[name=Ada,advancements={rainsenchants:shardlands=true}]").contains("passed"));
            // --- a monster that spawned by itself, killed by Ada, drops a shard -------------------------------
            String[] kinds = {"zombie", "creeper", "spider", "skeleton", "enderman", "witch"};
            Await.until("a monster dies to Ada and drops a shard", Duration.ofSeconds(60), () -> {
                for (String kind : kinds) {
                    server.console("execute at Ada as @e[type=minecraft:" + kind + ",distance=..96,limit=1,sort=nearest]"
                            + " run damage @s 1000 minecraft:player_attack by Ada");
                }
                return server.console("execute if entity @a[name=Ada,advancements={rainsenchants:first_shard=true}]")
                        .contains("passed");
            });
            assertThat(server.console("execute at Ada if entity @e[type=item,distance=..128,nbt={Item:{id:\"minecraft:echo_shard\"}}]"))
                    .as("the shard lies where the monster fell").contains("passed");

            server.console("execute in minecraft:shardlands run forceload add 100 100");
            frame(server, "minecraft:shardlands", 100, 200, 100);
            server.console("execute in minecraft:shardlands run fill 100 200 100 101 202 100 minecraft:nether_portal[axis=x]");
            Await.ticks(120);
            server.console("execute in minecraft:shardlands run tp Ada 100.5 200 100.5");
            Await.until("Ada is home again", Duration.ofSeconds(30), () -> !ada.world().contains("shardlands"));
            server.console("tp Ada 0.5 " + (Y - 1) + " 0.5");

            // --- shards: handed out by staff, sold to the shop --------------------------------------------
            server.console("clear Ada");
            assertThat(server.console("enchants shard Ada wisdom 5")).contains("Gave Ada 5");
            Await.until("they arrive", WAIT, () -> count(server, "echo_shard") == 5);
            long before = balance(ada);
            ada.run("sell hand");
            Await.until("the shop paid 20 each", WAIT, () -> balance(ada) == before + 100);
            assertThat(count(server, "echo_shard")).isZero();

            // --- a guild pays 100 Telekinesis shards in and unlocks it -------------------------------------
            ada.run("guild create Shard Hunters SHH");
            Await.until("founded", WAIT, () -> told(ada, "Done."));
            server.console("enchants shard Ada telekinesis 120");
            Await.until("they arrive", WAIT, () -> count(server, "echo_shard") == 120);
            ada.forgetChat();
            ada.run("guild");
            ada.awaitWindow("Shard Hunters");
            ada.click("Enchantments");
            ada.awaitWindow("Enchantments");
            ada.click("Telekinesis");
            Await.until("unlocked", WAIT, () -> told(ada, "Your guild unlocked"));
            assertThat(count(server, "echo_shard")).as("only what was needed").isEqualTo(20);
            ada.closeWindow();

            // --- the shop sells its book for shards and money; a locked one is refused -------------------
            long beforeBook = balance(ada);
            ada.run("shop");
            ada.awaitWindow("Shop");
            ada.click("Rain's enchantments");
            ada.awaitWindow("Rain's books");
            ada.forgetChat();
            ada.click("Launch I");
            Await.until("Launch is locked", WAIT, () -> told(ada, "has not unlocked"));
            ada.click("Telekinesis I");
            Await.until("bought", WAIT, () -> told(ada, "You bought"));
            ada.closeWindow();
            assertThat(count(server, "enchanted_book")).isEqualTo(1);
            assertThat(count(server, "echo_shard")).as("eight shards for level I").isEqualTo(12);
            assertThat(balance(ada)).as("and 250 coins").isEqualTo(beforeBook - 250);

            // Known and not this scenario's: founding a guild reads and writes its tables on the server thread
            // (GuildSql, ProgressBook). Those places only; the shard book's own work must never show up here.
            assertThat(server.paper.errorsFrom("RainsCore", "RainsEnchants", "RainsEconomy", "RainsGuilds").stream()
                    .filter(line -> !line.contains("modules.guilds.store.GuildSql.")
                            && !line.contains("modules.guilds.store.ProgressBook.")))
                    .isEmpty();
        }
    }
}
