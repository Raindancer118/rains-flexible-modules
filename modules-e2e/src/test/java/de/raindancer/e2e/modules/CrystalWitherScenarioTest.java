package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Crystal Wither on a real server: amethyst blocks in a wither's T with three wither skeleton skulls on top raise a
 * Crystal Wither in the shard world the moment a player places the last skull — and the same build in the overworld
 * stays a build.
 */
@Tag("e2e")
class CrystalWitherScenarioTest {

    private static void build(Server server, String in) {
        server.console(in + "fill -4 300 -4 4 300 6 minecraft:stone");
        server.console(in + "fill -4 301 -4 4 306 6 minecraft:air");
        server.console(in + "setblock 0 301 0 minecraft:amethyst_block");
        server.console(in + "fill -1 302 0 1 302 0 minecraft:amethyst_block");
        server.console(in + "setblock -1 303 0 minecraft:wither_skeleton_skull");
        server.console(in + "setblock 1 303 0 minecraft:wither_skeleton_skull");
    }

    private static void lastSkull(Server server, Bot builder, String in) {
        server.console("clear Builder");
        server.console("give Builder wither_skeleton_skull");
        server.console(in + "tp Builder 0.5 301 3.5 180 30");
        Await.ticks(20);
        builder.hold(builder.hotbarSlotOf(item -> item.material().toLowerCase().contains("skull")));
        builder.useOn(0, 302, 0);
        Await.ticks(10);
    }

    @Test
    @DisplayName("the amethyst wither rises as a Crystal Wither in the shard world, and nowhere else")
    void rises() {
        try (Server server = Server.start("crystal-wither", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            String shard = "execute in minecraft:shardlands run ";
            server.console(shard + "forceload add -16 -16 16 16");
            server.console("forceload add -16 -16 16 16");
            Bot builder = server.player("Builder");
            server.console("gamemode survival Builder");

            build(server, "");
            lastSkull(server, builder, "");
            assertThat(server.console("execute if entity @e[type=wither]")).as("no wither in the overworld")
                    .doesNotContain("passed");
            assertThat(server.console("execute if block 0 301 0 minecraft:amethyst_block")).contains("passed");

            build(server, shard);
            lastSkull(server, builder, shard);
            assertThat(server.console(shard + "execute if entity @e[type=wither]")).as("a wither rose").contains("passed");
            assertThat(server.console(shard + "data get entity @e[type=wither,limit=1] CustomName")).contains("Crystal");
            assertThat(server.console(shard + "execute if block 0 301 0 minecraft:air")).as("the build is gone")
                    .contains("passed");
        }
    }
}
