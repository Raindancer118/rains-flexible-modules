package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code content.yml} into {@link Content}. Nothing in the file stops the islands: a line that cannot be read is named
 * in {@link Result#problems()} and left out, so an owner's typo costs one upgrade and a log line, not the server.
 */
public final class ContentReader {

    public record Result(Content content, List<String> problems) {
    }

    private static final int SHARD_EVERY = 10;
    private static final int TEAM_SIZE = 6;
    private static final int MOB_CAP = 12;

    private ContentReader() {
    }

    public static Result read(ConfigurationSection yaml) {
        List<String> problems = new ArrayList<>();
        Map<IslandDimension, List<Content.Weighted>> pools = new EnumMap<>(IslandDimension.class);
        ConfigurationSection poolSection = yaml.getConfigurationSection("pools");
        if (poolSection != null) {
            for (String key : poolSection.getKeys(false)) {
                Optional<IslandDimension> dimension = IslandDimension.byId(key);
                if (dimension.isEmpty()) {
                    problems.add("pools." + key + ": no such dimension (overworld, nether, end)");
                    continue;
                }
                pools.put(dimension.get(), pool(poolSection.getConfigurationSection(key), "pools." + key, problems));
            }
        }
        Map<String, Upgrade> upgrades = new LinkedHashMap<>();
        ConfigurationSection upgradeSection = yaml.getConfigurationSection("upgrades");
        if (upgradeSection != null) {
            for (String id : upgradeSection.getKeys(false)) {
                upgrade(id, upgradeSection.getConfigurationSection(id), problems).ifPresent(up -> upgrades.put(id, up));
            }
        }
        Map<IslandDimension, Long> prices = new EnumMap<>(IslandDimension.class);
        prices.put(IslandDimension.NETHER, yaml.getLong("unlock.nether", 400));
        prices.put(IslandDimension.END, yaml.getLong("unlock.end", 1200));
        Content content = new Content(pools, upgrades, prices, yaml.getInt("shards.every-blocks", SHARD_EVERY),
                yaml.getLong("shards.amount", 1), yaml.getInt("team-size", TEAM_SIZE), yaml.getInt("mob-cap", MOB_CAP));
        return new Result(content, List.copyOf(problems));
    }

    private static List<Content.Weighted> pool(ConfigurationSection section, String path, List<String> problems) {
        List<Content.Weighted> pool = new ArrayList<>();
        if (section == null) {
            return pool;
        }
        for (String name : section.getKeys(false)) {
            Material material = Material.matchMaterial(name);
            int weight = section.getInt(name, 0);
            if (material == null || !material.isBlock()) {
                problems.add(path + "." + name + ": not a block");
            } else if (weight <= 0) {
                problems.add(path + "." + name + ": a weight must be above zero");
            } else {
                pool.add(new Content.Weighted(material, weight));
            }
        }
        return pool;
    }

    private static Optional<Upgrade> upgrade(String id, ConfigurationSection section, List<String> problems) {
        String path = "upgrades." + id;
        if (section == null) {
            problems.add(path + ": not a section");
            return Optional.empty();
        }
        Optional<Upgrade.Kind> kind = Upgrade.Kind.fromWire(section.getString("kind", "block"));
        if (kind.isEmpty()) {
            problems.add(path + ".kind: '" + section.getString("kind") + "' is none of block, mob, smelt, auto-break");
            return Optional.empty();
        }
        Material block = null;
        EntityType mob = null;
        if (kind.get() == Upgrade.Kind.BLOCK) {
            block = Material.matchMaterial(section.getString("block", ""));
            if (block == null || !block.isBlock()) {
                problems.add(path + ".block: '" + section.getString("block") + "' is not a block");
                return Optional.empty();
            }
        } else {
            block = Optional.ofNullable(Material.matchMaterial(section.getString("icon", ""))).orElse(Material.CHEST);
        }
        if (kind.get() == Upgrade.Kind.MOB) {
            mob = entity(section.getString("mob", ""));
            if (mob == null || !mob.isAlive() || !mob.isSpawnable()) {
                problems.add(path + ".mob: '" + section.getString("mob") + "' is not a mob that can be spawned");
                return Optional.empty();
            }
        }
        Set<IslandDimension> dimensions = EnumSet.noneOf(IslandDimension.class);
        for (String raw : section.getStringList("dimensions")) {
            Optional<IslandDimension> dimension = IslandDimension.byId(raw);
            if (dimension.isEmpty()) {
                problems.add(path + ".dimensions: no such dimension '" + raw + "'");
            } else {
                dimensions.add(dimension.get());
            }
        }
        if (dimensions.isEmpty()) {
            problems.add(path + ": in no dimension, so nobody could buy it");
            return Optional.empty();
        }
        List<Upgrade.Level> levels = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("levels")) {
            if (raw.get("chance") instanceof Number chance && raw.get("price") instanceof Number price
                    && chance.doubleValue() > 0 && price.longValue() >= 0) {
                levels.add(new Upgrade.Level(Math.min(100, chance.doubleValue()), price.longValue()));
            } else {
                problems.add(path + ".levels: " + raw + " needs a chance above 0 and a price");
            }
        }
        if (levels.isEmpty()) {
            problems.add(path + ": no levels");
            return Optional.empty();
        }
        return Optional.of(new Upgrade(id, section.getString("name", id), kind.get(), block, mob, dimensions, levels));
    }

    private static EntityType entity(String name) {
        try {
            return EntityType.valueOf(name.strip().toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
