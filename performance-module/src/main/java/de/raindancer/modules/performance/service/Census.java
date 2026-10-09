package de.raindancer.modules.performance.service;

import de.raindancer.modules.performance.model.ChunkCensus;
import de.raindancer.modules.performance.model.EntityGroup;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Counts what every loaded chunk holds. Reads every world, so only on a server with one main thread —
 * Paper, called from the global region (which is that thread). No decisions here; FindingRule makes them.
 */
public final class Census {

    /** A chunk with fewer block entities than this is not worth listing them for. */
    private static final int FEWEST_BLOCK_ENTITIES = 10;

    public List<ChunkCensus> take(Iterable<World> worlds) {
        Map<String, ChunkCensus> chunks = new HashMap<>();
        for (World world : worlds) {
            String key = world.getKey().asString();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                Location at = entity.getLocation();
                int cx = at.getBlockX() >> 4;
                int cz = at.getBlockZ() >> 4;
                chunks.computeIfAbsent(key + "/" + cx + "/" + cz, ignored -> new ChunkCensus(key, cx, cz))
                        .seen(typeOf(entity), groupOf(entity), at.getX(), at.getY(), at.getZ());
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                BlockState[] states = chunk.getTileEntities(false);
                if (states.length < FEWEST_BLOCK_ENTITIES) {
                    continue;
                }
                ChunkCensus census = chunks.computeIfAbsent(key + "/" + chunk.getX() + "/" + chunk.getZ(),
                        ignored -> new ChunkCensus(key, chunk.getX(), chunk.getZ()));
                for (BlockState state : states) {
                    census.blockEntities(state.getType().getKey().getKey(), 1);
                }
            }
        }
        return new ArrayList<>(chunks.values());
    }

    static String typeOf(Entity entity) {
        return entity.getType().getKey().getKey().toLowerCase(Locale.ROOT);
    }

    static EntityGroup groupOf(Entity entity) {
        if (entity instanceof Item || entity instanceof ExperienceOrb) {
            return EntityGroup.ITEM;
        }
        if (entity instanceof AbstractVillager) {
            return EntityGroup.VILLAGER;
        }
        if (entity instanceof Breedable) {
            return EntityGroup.ANIMAL;
        }
        if (entity instanceof Enemy) {
            return EntityGroup.MONSTER;
        }
        if (entity instanceof Vehicle) {
            return EntityGroup.VEHICLE;
        }
        return EntityGroup.OTHER;
    }
}
