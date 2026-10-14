package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.rules.ShardBiomes;
import de.raindancer.modules.enchants.service.ShardWorldService;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Switches each of the shard world's chunks to the purple copies of its biomes, once, right after it is made or first
 * loaded — terrain, ores and structures are the game's own, made with the game's biomes; only what they look like
 * changes. Without the datapack (it failed to load) nothing is changed.
 */
public final class ShardBiomeListener implements IEnchantsListener {

    private static final NamespacedKey DONE = new NamespacedKey(ShardBiomes.NAMESPACE, "purple");
    private static final int CELL = 4;

    private final ShardWorldService worlds;
    private final Map<Biome, Optional<Biome>> copies = new ConcurrentHashMap<>();

    public ShardBiomeListener(ShardWorldService worlds) {
        this.worlds = worlds;
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent event) {
        if (worlds.isShardWorld(event.getWorld())) {
            convert(event.getChunk());
        }
    }

    /** Converts a chunk not converted yet. @return whether anything changed */
    public boolean convert(Chunk chunk) {
        if (chunk.getPersistentDataContainer().has(DONE)) {
            return false;
        }
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        boolean any = false;
        for (int x = 0; x < 16; x += CELL) {
            for (int z = 0; z < 16; z += CELL) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += CELL) {
                    Biome now = world.getBiome(baseX + x, y, baseZ + z);
                    Optional<Biome> copy = copies.computeIfAbsent(now, ShardBiomeListener::copyOf);
                    if (copy.isPresent()) {
                        world.setBiome(baseX + x, y, baseZ + z, copy.get());
                        any = true;
                    }
                }
            }
        }
        // Marked even when nothing had a copy, so a chunk is looked at once; but not when the pack is missing.
        if (any || copies.values().stream().anyMatch(Optional::isPresent)) {
            chunk.getPersistentDataContainer().set(DONE, PersistentDataType.BYTE, (byte) 1);
        }
        return any;
    }

    private static Optional<Biome> copyOf(Biome biome) {
        String key = biome.getKey().asString();
        if (ShardBiomes.copyOf(key).isPresent()) {
            return Optional.empty();
        }
        return Optional.ofNullable(RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                .get(Key.key(ShardBiomes.copyKey(key))));
    }
}
