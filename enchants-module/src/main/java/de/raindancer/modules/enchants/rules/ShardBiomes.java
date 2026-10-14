package de.raindancer.modules.enchants.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shard world's biomes: each vanilla biome again, the same in everything the game does with it (spawns, weather,
 * features, its tags) but under a purple sky, fog and light. Minecraft has no light colour of its own a server could
 * set; it is the biome's — so the shard world's chunks are switched to these copies once they are made.
 */
public final class ShardBiomes {

    public static final String NAMESPACE = "rainsenchants";
    private static final String PREFIX = "shard_";

    /** What makes a copy purple: the biome's own visual attributes, set over the dimension's. */
    public static final Map<String, String> VISUALS = Map.of(
            "minecraft:visual/sky_color", "#4a2a6e",
            "minecraft:visual/fog_color", "#3a1f52",
            "minecraft:visual/sky_light_color", "#b27aff",
            "minecraft:visual/ambient_light_color", "#1a0c26",
            "minecraft:visual/cloud_color", "#cc6a4a8a",
            "minecraft:visual/water_fog_color", "#2e1a46");

    private ShardBiomes() {
    }

    /** {@code minecraft:plains} → {@code rainsenchants:shard_plains}. */
    public static String copyKey(String vanilla) {
        return NAMESPACE + ":" + PREFIX + vanilla.substring(vanilla.indexOf(':') + 1);
    }

    /** The vanilla biome a copy was made of; empty for anything not a copy. */
    public static Optional<String> copyOf(String key) {
        String start = NAMESPACE + ":" + PREFIX;
        return key.startsWith(start) ? Optional.of("minecraft:" + key.substring(start.length())) : Optional.empty();
    }

    /** The copy of a biome's definition: everything kept, the visuals purple. */
    public static JsonObject purple(JsonObject vanilla) {
        JsonObject copy = vanilla.deepCopy();
        JsonObject attributes = copy.has("attributes") ? copy.getAsJsonObject("attributes") : new JsonObject();
        VISUALS.forEach(attributes::addProperty);
        copy.add("attributes", attributes);
        return copy;
    }

    /**
     * The biome tags with the copies of the biomes each names, added to the game's own (not replacing them): tags
     * decide slimes in swamps, drowned in oceans, which villagers live where. A tag naming another tag needs nothing
     * — that one gets its copies itself.
     *
     * @param vanilla the game's biome tags, by path under {@code minecraft:}
     * @param copied  the biomes that have a copy
     */
    public static Map<String, JsonObject> tags(Map<String, JsonObject> vanilla, Set<String> copied) {
        Map<String, JsonObject> tags = new LinkedHashMap<>();
        vanilla.forEach((path, tag) -> {
            JsonArray values = new JsonArray();
            JsonArray named = tag.has("values") ? tag.getAsJsonArray("values") : new JsonArray();
            for (JsonElement value : named) {
                String biome = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
                if (!biome.startsWith("#") && copied.contains(biome)) {
                    values.add(copyKey(biome));
                }
            }
            if (!values.isEmpty()) {
                JsonObject ours = new JsonObject();
                ours.addProperty("replace", false);
                ours.add("values", values);
                tags.put(path, ours);
            }
        });
        return tags;
    }

    /** The pack's {@code pack.mcmeta}, for the data format of the game running it. */
    public static String packMeta(int dataFormat) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Rain's Enchants: the shard world's purple biomes");
        pack.addProperty("min_format", dataFormat);
        pack.addProperty("max_format", dataFormat);
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        return meta.toString();
    }
}
