package de.raindancer.modules.enchants.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.EnchantsSettings;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.SpawnCategory;

import java.util.Optional;
import java.util.function.Supplier;

/** The shard world: amplified, eternal midnight, hard, more monsters, a world border. */
public final class ShardWorldService {

    private static final long MIDNIGHT = 18_000L;

    private final Supplier<EnchantsSettings> settings;
    private final LogChannel log;

    public ShardWorldService(Supplier<EnchantsSettings> settings, LogChannel log) {
        this.settings = settings;
        this.log = log;
    }

    /** Loads it, creating it the first time, and sets it up. Empty when switched off or not creatable here. */
    public Optional<World> ensure() {
        EnchantsSettings config = settings.get();
        if (!config.shardWorldEnabled()) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(config.shardWorld());
        if (world == null) {
            if (Scheduling.isFolia()) {
                log.warn("The shard world '{}' does not exist, and Folia cannot create worlds while running. "
                        + "Create it beforehand (amplified) and restart.", config.shardWorld());
                return Optional.empty();
            }
            log.info("Creating the shard world '{}' (amplified) — the first start takes a moment.", config.shardWorld());
            world = new WorldCreator(config.shardWorld()).environment(World.Environment.NORMAL)
                    .type(WorldType.AMPLIFIED).createWorld();
            if (world == null) {
                log.warn("The shard world '{}' could not be created.", config.shardWorld());
                return Optional.empty();
            }
        }
        configure(world, config);
        return Optional.of(world);
    }

    private static void configure(World world, EnchantsSettings config) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(MIDNIGHT);
        world.setDifficulty(Difficulty.HARD);
        world.setSpawnLimit(SpawnCategory.MONSTER, config.shardWorldMonsters());
        world.setTicksPerSpawns(SpawnCategory.MONSTER, 1);
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(config.shardWorldRadius() * 2.0);
    }

    public boolean isShardWorld(World world) {
        EnchantsSettings config = settings.get();
        return world != null && config.shardWorldEnabled() && world.getName().equals(config.shardWorld());
    }

    public Optional<World> world() {
        EnchantsSettings config = settings.get();
        return config.shardWorldEnabled() ? Optional.ofNullable(Bukkit.getWorld(config.shardWorld())) : Optional.empty();
    }
}
