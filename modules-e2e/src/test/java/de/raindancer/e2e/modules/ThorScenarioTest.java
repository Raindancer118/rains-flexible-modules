package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thor on a real server: an axe thrown like Mjölnir hits and stays in the inventory; a trident striking a wall calls a
 * storm that hurts what stands beside it; and the shard world's rain spares its endermen, unlike the overworld's.
 */
@Tag("e2e")
class ThorScenarioTest {

    private static final int Y = 64;

    private static double health(Server server, String type, String tag) {
        Matcher found = Pattern.compile("data: ([0-9.]+)f").matcher(
                server.console("data get entity @e[type=" + type + ",tag=" + tag + ",limit=1] Health"));
        return found.find() ? Double.parseDouble(found.group(1)) : -1;
    }

    private static int count(Server server, String item) {
        Matcher found = Pattern.compile("Found (\\d+)").matcher(server.console("clear Thrower " + item + " 0"));
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    @Test
    @DisplayName("Mjölnir hits and stays yours; a trident's storm strikes beside it; the shard world's rain spares endermen")
    void thor() {
        try (Server server = Server.start("thor", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            server.console("time set midnight");
            server.console("gamerule doDaylightCycle false");
            server.console("forceload add -16 -32 16 16");
            server.console("fill -8 " + (Y - 1) + " -32 16 " + (Y - 1) + " 8 minecraft:stone");
            server.console("fill -8 " + Y + " -32 16 " + (Y + 6) + " 8 minecraft:air");
            Bot thrower = server.player("Thrower");
            server.console("gamemode survival Thrower");

            // --- Mjölnir: thrown, it hits; the axe never leaves the inventory --------------------------------
            server.console("summon minecraft:husk 0.5 " + Y + " -8.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"hammered\"]}");
            server.console("give Thrower netherite_axe[enchantments={\"rainsenchants:thor\":3}]");
            server.console("tp Thrower 0.5 " + Y + " 0.5 180 0");
            Await.ticks(20);
            thrower.hold(thrower.hotbarSlotOf(item -> item.material().toLowerCase().contains("netherite_axe")));
            thrower.look(180, 4).useHeld();
            Await.ticks(60);
            assertThat(health(server, "husk", "hammered")).as("the hammer hit").isBetween(0.0, 19.9);
            assertThat(count(server, "netherite_axe")).as("the axe stayed in the inventory").isEqualTo(1);
            server.console("kill @e[type=husk]");

            // --- the trident strikes a wall between two husks; its storm hurts both --------------------------
            server.console("fill 0 " + Y + " -6 0 " + (Y + 3) + " -6 minecraft:stone");
            server.console("summon minecraft:husk -0.5 " + Y + " -4.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"left\"]}");
            server.console("summon minecraft:husk 1.5 " + Y + " -4.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"right\"]}");
            server.console("clear Thrower");
            server.console("give Thrower trident[enchantments={\"rainsenchants:thor\":3}]");
            server.console("tp Thrower 0.5 " + Y + " 0.5 180 0");
            Await.ticks(20);
            thrower.hold(thrower.hotbarSlotOf(item -> item.material().toLowerCase().contains("trident")));
            thrower.look(180, 0).useHeld();
            Await.ticks(15);
            thrower.look(180, 0).releaseUse();
            Await.ticks(60);
            assertThat(health(server, "husk", "left")).as("the storm struck the left husk").isBetween(0.0, 19.9);
            assertThat(health(server, "husk", "right")).as("the storm struck the right husk").isBetween(0.0, 19.9);

            // --- rain: the shard world's spares its endermen, the overworld's does not --------------------------
            server.console("execute in minecraft:shardlands run forceload add -16 -16 16 16");
            // A pen open to the sky: the rain gets in, an enderman wandering about does not get out.
            for (String in : new String[]{"execute in minecraft:shardlands run ", ""}) {
                server.console(in + "fill -3 300 -3 3 303 3 minecraft:glass");
                server.console(in + "fill -2 301 -2 2 303 2 minecraft:air");
                server.console(in + "fill -2 300 -2 2 300 2 minecraft:stone");
            }
            server.console("execute in minecraft:shardlands run weather rain");
            server.console("weather rain");
            server.console("execute in minecraft:shardlands run summon minecraft:enderman 0.5 301 0.5 "
                    + "{PersistenceRequired:1b,Tags:[\"shard\"]}");
            server.console("summon minecraft:enderman 0.5 301 0.5 {PersistenceRequired:1b,Tags:[\"over\"]}");
            Await.ticks(100);
            assertThat(health(server, "enderman", "shard")).as("unhurt by the shard world's rain").isEqualTo(40.0);
            assertThat(server.console("execute in minecraft:shardlands if entity @e[type=enderman,tag=shard,x=-3,y=299,z=-3,dx=6,dy=4,dz=6]"))
                    .as("and not fled from it").contains("passed");
            boolean hurt = health(server, "enderman", "over") < 40.0;
            boolean fled = !server.console("execute if entity @e[type=enderman,tag=over,x=-3,y=299,z=-3,dx=6,dy=4,dz=6]")
                    .contains("passed");
            assertThat(hurt || fled).as("the overworld's rain drives its enderman off").isTrue();
        }
    }
}
