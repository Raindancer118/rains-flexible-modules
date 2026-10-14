package de.raindancer.modules.enchants.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShardBiomesTest {

    private static final String PLAINS = """
            {"attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawn_costs": {}}},
                            "minecraft:visual/sky_color": "#78a7ff"},
             "carvers": [], "downfall": 0.4, "effects": {"water_color": "#3f76e4"},
             "features": [[], ["minecraft:ore_coal_upper"]], "has_precipitation": true, "temperature": 0.8}
            """;

    @Test
    @DisplayName("a biome's copy is named after it, under the shard world's own name")
    void names() {
        assertThat(ShardBiomes.copyKey("minecraft:plains")).isEqualTo("rainsenchants:shard_plains");
        assertThat(ShardBiomes.copyOf("rainsenchants:shard_plains")).hasValue("minecraft:plains");
        assertThat(ShardBiomes.copyOf("minecraft:plains")).isEmpty();
    }

    @Test
    @DisplayName("the copy is the biome as it is — spawns, features, weather — under a purple sky, fog and light")
    void purple() {
        JsonObject copy = ShardBiomes.purple(JsonParser.parseString(PLAINS).getAsJsonObject());
        JsonObject attributes = copy.getAsJsonObject("attributes");

        assertThat(attributes.has("minecraft:gameplay/natural_mob_spawns")).isTrue();
        assertThat(copy.get("temperature").getAsDouble()).isEqualTo(0.8);
        assertThat(copy.getAsJsonArray("features").toString()).contains("ore_coal_upper");
        for (String visual : ShardBiomes.VISUALS.keySet()) {
            assertThat(attributes.get(visual).getAsString()).as(visual).startsWith("#");
        }
        assertThat(attributes.get("minecraft:visual/sky_light_color").getAsString())
                .isNotEqualTo("#78a7ff").isEqualTo(ShardBiomes.VISUALS.get("minecraft:visual/sky_light_color"));
    }

    @Test
    @DisplayName("every biome tag holds the copies of the biomes it names, so the game treats them alike")
    void tags() {
        Map<String, JsonObject> vanilla = Map.of(
                "is_ocean", JsonParser.parseString(
                        "{\"values\": [\"#minecraft:is_deep_ocean\", \"minecraft:ocean\", \"minecraft:warm_ocean\"]}")
                        .getAsJsonObject(),
                "allows_surface_slime_spawns", JsonParser.parseString(
                        "{\"values\": [\"minecraft:swamp\"]}").getAsJsonObject());

        Map<String, JsonObject> tags = ShardBiomes.tags(vanilla, Set.of("minecraft:ocean", "minecraft:warm_ocean"));

        assertThat(tags).containsOnlyKeys("is_ocean");
        assertThat(tags.get("is_ocean").get("replace").getAsBoolean()).isFalse();
        assertThat(tags.get("is_ocean").getAsJsonArray("values").toString())
                .contains("rainsenchants:shard_ocean", "rainsenchants:shard_warm_ocean")
                .doesNotContain("deep_ocean");
    }

    @Test
    @DisplayName("the pack says the data format of the game it was written for")
    void meta() {
        JsonObject meta = JsonParser.parseString(ShardBiomes.packMeta(121)).getAsJsonObject().getAsJsonObject("pack");
        assertThat(meta.get("min_format").getAsInt()).isEqualTo(121);
        assertThat(meta.get("max_format").getAsInt()).isEqualTo(121);
    }
}
