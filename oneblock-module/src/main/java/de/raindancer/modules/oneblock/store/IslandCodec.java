package de.raindancer.modules.oneblock.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An island as one JSON document, the row's {@code data}. A field this version does not know is skipped and one it
 * expects but does not find takes its default, so an island written by a newer or older jar still reads.
 */
public final class IslandCodec {

    private IslandCodec() {
    }

    public static String write(Island island) {
        JsonObject json = new JsonObject();
        json.addProperty("id", island.id().toString());
        json.addProperty("owner", island.owner().toString());
        json.addProperty("ownerName", island.ownerName());
        json.addProperty("mode", island.mode().wire());
        json.addProperty("created", island.created());
        json.addProperty("shards", island.shards());
        JsonArray members = new JsonArray();
        island.members().forEach(member -> members.add(member.toString()));
        json.add("members", members);
        JsonObject blocks = new JsonObject();
        island.blocks().forEach((dimension, block) -> {
            JsonObject one = new JsonObject();
            one.addProperty("x", block.spot().x());
            one.addProperty("y", block.spot().y());
            one.addProperty("z", block.spot().z());
            one.addProperty("broken", block.broken());
            JsonObject upgrades = new JsonObject();
            block.upgrades().forEach(upgrades::addProperty);
            one.add("upgrades", upgrades);
            blocks.add(dimension.id(), one);
        });
        json.add("blocks", blocks);
        JsonObject spawns = new JsonObject();
        island.spawns().forEach((dimension, spawn) -> {
            JsonObject one = new JsonObject();
            one.addProperty("x", spawn.x());
            one.addProperty("y", spawn.y());
            one.addProperty("z", spawn.z());
            one.addProperty("yaw", spawn.yaw());
            one.addProperty("pitch", spawn.pitch());
            spawns.add(dimension.id(), one);
        });
        json.add("spawns", spawns);
        json.addProperty("autoUnload", island.autoUnload());
        island.hold().ifPresent(hold -> {
            JsonObject held = new JsonObject();
            held.addProperty("kind", hold.kind().wire());
            held.addProperty("until", hold.until());
            held.addProperty("by", hold.by());
            json.add("hold", held);
        });
        return json.toString();
    }

    /** Empty when the document is not an island at all — no id or no owner. */
    public static Optional<Island> read(String text) {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            UUID id = UUID.fromString(json.get("id").getAsString());
            UUID owner = UUID.fromString(json.get("owner").getAsString());
            List<UUID> members = new ArrayList<>();
            if (json.has("members")) {
                for (JsonElement member : json.getAsJsonArray("members")) {
                    members.add(UUID.fromString(member.getAsString()));
                }
            }
            Map<IslandDimension, IslandBlock> blocks = new EnumMap<>(IslandDimension.class);
            if (json.has("blocks")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("blocks").entrySet()) {
                    Optional<IslandDimension> dimension = IslandDimension.byId(entry.getKey());
                    if (dimension.isEmpty()) {
                        continue;
                    }
                    JsonObject one = entry.getValue().getAsJsonObject();
                    Map<String, Integer> upgrades = new LinkedHashMap<>();
                    if (one.has("upgrades")) {
                        one.getAsJsonObject("upgrades").entrySet()
                                .forEach(upgrade -> upgrades.put(upgrade.getKey(), upgrade.getValue().getAsInt()));
                    }
                    blocks.put(dimension.get(), new IslandBlock(new BlockSpot(one.get("x").getAsInt(),
                            one.get("y").getAsInt(), one.get("z").getAsInt()), number(one, "broken"), upgrades));
                }
            }
            if (!blocks.containsKey(IslandDimension.OVERWORLD)) {
                blocks.put(IslandDimension.OVERWORLD, IslandBlock.fresh(IslandDimension.OVERWORLD.start()));
            }
            Map<IslandDimension, Spawn> spawns = new EnumMap<>(IslandDimension.class);
            if (json.has("spawns")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("spawns").entrySet()) {
                    IslandDimension.byId(entry.getKey()).ifPresent(dimension -> {
                        JsonObject one = entry.getValue().getAsJsonObject();
                        spawns.put(dimension, new Spawn(one.get("x").getAsDouble(), one.get("y").getAsDouble(),
                                one.get("z").getAsDouble(), one.get("yaw").getAsFloat(), one.get("pitch").getAsFloat()));
                    });
                }
            }
            Optional<Hold> hold = Optional.empty();
            if (json.has("hold")) {
                JsonObject held = json.getAsJsonObject("hold");
                hold = Hold.Kind.fromWire(held.get("kind").getAsString())
                        .map(kind -> new Hold(kind, held.get("until").getAsLong(),
                                held.has("by") ? held.get("by").getAsString() : ""));
            }
            return Optional.of(new Island(id, owner, json.has("ownerName") ? json.get("ownerName").getAsString() : "",
                    EconomyMode.fromWire(json.has("mode") ? json.get("mode").getAsString() : null).orElse(EconomyMode.UNDECIDED),
                    number(json, "created"), number(json, "shards"), members, blocks, spawns,
                    !json.has("autoUnload") || json.get("autoUnload").getAsBoolean(), hold));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static long number(JsonObject json, String field) {
        return json.has(field) ? json.get(field).getAsLong() : 0;
    }
}
