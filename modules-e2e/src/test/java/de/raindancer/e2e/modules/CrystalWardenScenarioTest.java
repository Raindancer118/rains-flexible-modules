package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Crystal Warden on a real server: with the odds turned all the way up, a monster coming by itself under the shard
 * world's open sky comes as a warden of a shard kind, and killed by a player it drops five times that kind's shards.
 */
@Tag("e2e")
class CrystalWardenScenarioTest {

    private static final String SHARD = "execute in minecraft:shardlands run ";
    /** Only what stands on the platform: the forced chunks have creatures of their own down in the dark. */
    private static final String HERE = "execute in minecraft:shardlands positioned 0.5 301 0.5 run ";

    @Test
    @DisplayName("under the open sky a monster comes as a Crystal Warden, which drops a lot of shards")
    void rises() {
        try (Server server = Server.start("crystal-warden", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            assertThat(server.console("settings set crystal-warden-per-mille 1000")).contains("is now");
            server.console(SHARD + "forceload add -16 -16 16 16");
            server.console(SHARD + "fill -8 300 -8 8 300 8 minecraft:stone");
            server.console(SHARD + "fill -8 301 -8 8 306 8 minecraft:air");
            server.player("Hunter");
            server.console("gamemode creative Hunter");
            server.console(SHARD + "tp Hunter 0.5 301 0.5");
            Await.ticks(20);

            server.console("enchants shardmob minecraft:zombie Hunter");
            Await.ticks(10);
            assertThat(server.console(HERE + "execute if entity @e[type=zombie,distance=..4]")).as("the zombie never came")
                    .doesNotContain("passed");
            assertThat(server.console(HERE + "execute if entity @e[type=warden,distance=..4]")).as("a warden came instead")
                    .contains("passed");
            assertThat(server.console(HERE + "data get entity @e[type=warden,distance=..4,limit=1] BukkitValues"))
                    .contains("rainsenchants:shard_tier").contains("rainsenchants:crystal_warden");

            server.console("clear Hunter");
            server.console(HERE + "damage @e[type=warden,distance=..4,limit=1] 100000 minecraft:player_attack by Hunter");
            Await.ticks(60);
            Matcher found = Pattern.compile("Found (\\d+)").matcher(server.console("clear Hunter minecraft:echo_shard 0"));
            assertThat(found.find() ? Integer.parseInt(found.group(1)) : 0).as("five times even a Shadowed one's shard")
                    .isGreaterThanOrEqualTo(6);

            // A warden that came any other way — a shrieker, staff — is not one of these, and not worth five times.
            assertThat(server.console("settings set crystal-warden-per-mille 0")).contains("is now");
            server.console("enchants shardmob minecraft:warden Hunter");
            Await.ticks(10);
            assertThat(server.console(HERE + "data get entity @e[type=warden,distance=..4,limit=1] BukkitValues"))
                    .contains("rainsenchants:shard_tier").doesNotContain("rainsenchants:crystal_warden");
        }
    }
}
