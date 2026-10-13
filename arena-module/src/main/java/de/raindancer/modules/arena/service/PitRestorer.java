package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.rules.BuildAreaRule;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pit whose fight may build and blow things up: its blocks are copied before the first round and put back before every
 * next one and when the fight is over, so the next fight starts on the map as it was built. The copy is per chunk and
 * taken, compared and restored on the region thread that owns the chunk; only blocks that differ are written.
 *
 * <p>The pit is open to changes only while its copy is complete — until then, and once it is let go, the world stays
 * locked as the map was built.
 */
public final class PitRestorer {

    private final Plugin plugin;
    private final Server server;
    private final BuildAreaRule rule = new BuildAreaRule();
    private final Map<String, Armed> armed = new ConcurrentHashMap<>();

    private static final class Armed {
        final Box area;
        final World world;
        final Map<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
        final AtomicInteger pending = new AtomicInteger();

        Armed(Box area, World world) {
            this.area = area;
            this.world = world;
        }
    }

    public PitRestorer(Plugin plugin, Server server) {
        this.plugin = plugin;
        this.server = server;
    }

    /** Before a round: a pit already copied is put back; one that is to be built in is copied; any other is let go. */
    public void prepare(Pit pit, boolean building) {
        Armed held = armed.get(pit.id());
        if (held != null) {
            restore(held);
            if (!building) {
                armed.remove(pit.id());
            }
            return;
        }
        if (building) {
            arm(pit);
        }
    }

    /** The fight in this pit is over and nobody else is in it: put back and let go. */
    public void finish(Pit pit) {
        Armed held = armed.remove(pit.id());
        if (held != null) {
            restore(held);
        }
    }

    /** Whether a plugin-opened pit covers this place right now. */
    public boolean isOpen(Location where) {
        if (where == null || where.getWorld() == null) {
            return false;
        }
        for (Armed each : armed.values()) {
            if (each.pending.get() == 0 && each.area.contains(where.getWorld().getName(), where.getX(), where.getY(),
                    where.getZ())) {
                return true;
            }
        }
        return false;
    }

    private void arm(Pit pit) {
        Box floor = pit.floor();
        World world = floor == null ? null : server.getWorld(floor.world());
        if (world == null) {
            return;
        }
        Armed copy = new Armed(rule.around(floor, world.getMinHeight(), world.getMaxHeight() - 1), world);
        forEachChunk(copy, (cx, cz) -> {
            copy.pending.incrementAndGet();
            Scheduling.region(plugin, centreOf(copy, cx, cz), () -> {
                try {
                    copy.chunks.put(key(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                } finally {
                    copy.pending.decrementAndGet();
                }
            });
        });
        armed.put(pit.id(), copy);
    }

    private void restore(Armed copy) {
        forEachChunk(copy, (cx, cz) -> Scheduling.region(plugin, centreOf(copy, cx, cz), () -> {
            ChunkSnapshot before = copy.chunks.get(key(cx, cz));
            if (before == null) {
                return;
            }
            Chunk chunk = copy.world.getChunkAt(cx, cz);
            int x0 = Math.max(copy.area.minX(), cx << 4);
            int x1 = Math.min(copy.area.maxX(), (cx << 4) + 15);
            int z0 = Math.max(copy.area.minZ(), cz << 4);
            int z1 = Math.min(copy.area.maxZ(), (cz << 4) + 15);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = copy.area.minY(); y <= copy.area.maxY(); y++) {
                        BlockData was = before.getBlockData(x & 15, y, z & 15);
                        Block now = chunk.getBlock(x & 15, y, z & 15);
                        if (!now.getBlockData().equals(was)) {
                            now.setBlockData(was, false);
                        }
                    }
                }
            }
        }));
    }

    private static void forEachChunk(Armed copy, java.util.function.BiConsumer<Integer, Integer> each) {
        for (int cx = copy.area.minX() >> 4; cx <= copy.area.maxX() >> 4; cx++) {
            for (int cz = copy.area.minZ() >> 4; cz <= copy.area.maxZ() >> 4; cz++) {
                each.accept(cx, cz);
            }
        }
    }

    private static Location centreOf(Armed copy, int cx, int cz) {
        return new Location(copy.world, (cx << 4) + 8, copy.area.minY(), (cz << 4) + 8);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
