package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shard world's purple biomes on a real server: the datapack Enchants writes at bootstrap loads without a
 * complaint, its biomes reach the client with their purple light, and the shard world's chunks are switched to them.
 */
@Tag("e2e")
class ShardBiomesScenarioTest {

    @Test
    @DisplayName("the purple biomes load, reach the client, and the shard world is made of them")
    void purple() {
        try (Server server = Server.start("shard-biomes", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            assertThat(server.log()).contains("purple biomes:").doesNotContain("could not be loaded")
                    .doesNotContain("Failed to load datapack");

            Bot player = server.player("Looker");
            var copy = player.registry("minecraft:worldgen/biome").stream()
                    .filter(entry -> entry.getId().asString().equals("rainsenchants:shard_plains")).findFirst();
            assertThat(copy).as("the copy of plains reached the client").isPresent();
            NbtMap attributes = ((NbtMap) copy.get().getData()).getCompound("attributes");
            assertThat(attributes.getString("minecraft:visual/sky_light_color")).isEqualToIgnoringCase("#b27aff");

            server.console("forceload add 0 0 0 0");
            server.console("execute in minecraft:shardlands run forceload add 0 0");
            assertThat(server.console("execute in minecraft:shardlands if biome 8 120 8 #rainsenchants:shard"))
                    .as("the shard world's chunk is purple").contains("passed");
            assertThat(server.console("execute in minecraft:overworld if biome 8 120 8 #rainsenchants:shard"))
                    .as("the overworld is left alone").doesNotContain("passed");
        }
    }
}
