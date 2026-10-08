package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.util.WorldGrid;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.OreDensity;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.model.RockBand;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Learns what the world holds by reading chunks as they load — a snapshot on the chunk's own thread,
 * counted on another — at most a couple of chunks a second, never the same chunk twice.
 */
public final class OreDensitySampler implements IModerationService {

    private static final long GAP_MILLIS = 400;
    private static final int MAX_REMEMBERED = 200_000;

    private final Plugin plugin;
    private final OreDensity density;
    private final Set<String> sampled = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastMillis = new AtomicLong();
    private volatile boolean enabled = true;

    public OreDensitySampler(Plugin plugin, OreDensity density) {
        this.plugin = plugin;
        this.density = density;
    }

    /** Offers a freshly loaded chunk. Call on the chunk's own thread. */
    public void offer(Chunk chunk) {
        World world = chunk.getWorld();
        if (!enabled || world.getEnvironment() == World.Environment.THE_END) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastMillis.get();
        if (now - last < GAP_MILLIS || !lastMillis.compareAndSet(last, now)) {
            return;
        }
        String key = world.getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (sampled.size() > MAX_REMEMBERED || !sampled.add(key)) {
            return;
        }
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        String environment = world.getEnvironment().name();
        int bottom = world.getMinHeight();
        int top = world.getEnvironment() == World.Environment.NETHER ? Math.min(world.getMaxHeight(), 122) : Math.min(world.getMaxHeight(), 80);
        Scheduling.async(plugin, () -> count(snapshot, environment, bottom, top));
    }

    void count(ChunkSnapshot snapshot, String environment, int bottom, int top) {
        Map<RockBand, long[]> enclosed = new HashMap<>();
        Map<RockBand, EnumMap<OreKind, Long>> ores = new HashMap<>();
        for (int y = bottom + 1; y < top - 1; y++) {
            RockBand band = RockBand.of(environment, y);
            for (int x = 1; x < 15; x++) {
                for (int z = 1; z < 15; z++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (WorldGrid.isOpen(type) || !enclosed(snapshot, x, y, z)) {
                        continue;
                    }
                    enclosed.computeIfAbsent(band, ignored -> new long[1])[0]++;
                    OreKind.of(type).ifPresent(kind ->
                            ores.computeIfAbsent(band, ignored -> new EnumMap<>(OreKind.class)).merge(kind, 1L, Long::sum));
                }
            }
        }
        enclosed.forEach((band, count) -> density.add(band, count[0], ores.getOrDefault(band, new EnumMap<>(OreKind.class))));
    }

    private static boolean enclosed(ChunkSnapshot snapshot, int x, int y, int z) {
        return !WorldGrid.isOpen(snapshot.getBlockType(x + 1, y, z)) && !WorldGrid.isOpen(snapshot.getBlockType(x - 1, y, z))
                && !WorldGrid.isOpen(snapshot.getBlockType(x, y + 1, z)) && !WorldGrid.isOpen(snapshot.getBlockType(x, y - 1, z))
                && !WorldGrid.isOpen(snapshot.getBlockType(x, y, z + 1)) && !WorldGrid.isOpen(snapshot.getBlockType(x, y, z - 1));
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.enabled = settings.xrayDetectionEnabled();
    }

    @Override
    public String describe() {
        return "learning how much ore the world holds, from the chunks themselves";
    }
}
