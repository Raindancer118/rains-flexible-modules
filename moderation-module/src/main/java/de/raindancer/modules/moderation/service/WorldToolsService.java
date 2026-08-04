package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.build.OreVein;
import de.raindancer.core.world.build.Veins;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Spawner;
import de.raindancer.core.world.spawn.Spawns;
import de.raindancer.core.world.spawn.Wave;
import de.raindancer.modules.moderation.ModerationSettings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The world tools: burying a vein, and calling up creatures.
 *
 * <h2>What is here and what is Core's</h2>
 * Almost none of it is here. What a vein looks like, what it is willing to replace, what a wave is and
 * where each creature lands are all {@code core.world.build} and {@code core.world.spawn} — values and
 * arithmetic, tested without a server. This class is the two seams they need ({@link Ground},
 * {@link Spawner}), the scheduling, and the record of who did it.
 *
 * <h2>Why the scheduling cannot be Core's</h2>
 * A {@link Wave} is a plan with timings in it. Turning one into a sequence of arrivals means running
 * work later <em>on the thread that owns the ground being spawned on</em>, which on Folia is a region
 * thread and on Paper is the server thread. Core cannot pick that on a caller's behalf without knowing
 * where the caller is, so the plan is Core's and the clock is the host's.
 *
 * <h2>Why a running wave can be stopped</h2>
 * Because it is the one thing in this module whose effect outlives the click. Twenty packs thirty
 * seconds apart is ten minutes during which somebody will realise it was aimed at the wrong place, and
 * a tool with no way to say "stop" is one nobody should be given.
 */
public final class WorldToolsService implements IModerationService {

    private final Plugin plugin;
    private final Server server;
    private final Audit audit;
    private final LogChannel log;

    /** Waves in flight, by the person who started them, so each can stop their own. */
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    private volatile ModerationSettings settings;

    public WorldToolsService(Plugin plugin, Server server, Audit audit, LogChannel log,
                             ModerationSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.audit = audit;
        this.log = log;
        this.settings = settings;
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh;
    }

    /** A wave that has not finished: its remaining tasks, and what it is for a message. */
    private record Running(List<ScheduledTask> tasks, Wave wave, AtomicInteger packsLeft) {
    }

    // ────────────────────────────────────────────────────────────────────────── ore

    /**
     * Buries a vein where {@code at} is.
     *
     * <p>The seed is the position, so a vein placed twice at the same block is the same vein — which
     * makes "did that work?" answerable by doing it again rather than by digging.
     */
    public Veins.Placed vein(Player by, Location at, String ore, int size) {
        Spot centre = spotOf(at);
        long seed = ((long) centre.x() << 32) ^ ((long) centre.z() << 16) ^ centre.y();
        Veins.Placed placed = new Veins(new WorldGround(at.getWorld()))
                .place(OreVein.around(centre, size, seed), ore);

        if (!placed.isEmpty()) {
            audit.record(AuditEntry.of("moderation", "spawn.ore")
                    .by(by.getUniqueId(), by.getName())
                    .in(at.getWorld().getName())
                    .saying(placed.blocks() + " × " + ore + " at " + centre));
        }
        return placed;
    }

    // ────────────────────────────────────────────────────────────────────────── creatures

    /**
     * Puts one pack down around {@code at}, now.
     *
     * <p>Straight through, no scheduling: a pack has one arrival and the caller is already on the
     * thread that owns the ground, because they clicked a button while standing on it.
     */
    public Spawns.Arrived pack(Player by, Location at, List<String> kinds, int howMany, int radius) {
        Wave.Pack one = Wave.justOne(kinds, howMany, radius).packs().getFirst();
        Spawns.Arrived arrived = new Spawns(new WorldSpawner(at.getWorld()))
                .place(one, spotOf(at), System.nanoTime());

        audit.record(AuditEntry.of("moderation", "spawn.pack")
                .by(by.getUniqueId(), by.getName())
                .in(at.getWorld().getName())
                .saying(arrived.spawned() + " creature(s): " + String.join(", ", kinds)));
        return arrived;
    }

    /**
     * Starts a wave around {@code at}.
     *
     * <p>One task per pack, each pinned to the region owning the ground — not one repeating task, so
     * that a pack which fails cannot stop the ones after it, and so stopping is cancelling a list
     * rather than unpicking a loop.
     *
     * @return whether it started. False when this person already has one running: two waves from one
     *         moderator is the state where "stop" has no unambiguous meaning
     */
    public boolean startWave(Player by, Location at, Wave wave) {
        if (wave.packs().isEmpty() || running.containsKey(by.getUniqueId())) {
            return false;
        }
        Spot centre = spotOf(at);
        World world = at.getWorld();
        AtomicInteger left = new AtomicInteger(wave.packs().size());
        List<ScheduledTask> tasks = new java.util.ArrayList<>(wave.packs().size());

        for (Wave.Pack pack : wave.packs()) {
            long delay = Math.max(1L, pack.afterTicks());
            tasks.add(Scheduling.regionTimer(plugin, at, delay, Long.MAX_VALUE, task -> {
                task.cancel();      // a timer used as a one-shot: regionLater has no equivalent here
                new Spawns(new WorldSpawner(world)).place(pack, centre, System.nanoTime());
                if (left.decrementAndGet() <= 0) {
                    running.remove(by.getUniqueId());
                }
            }));
        }
        running.put(by.getUniqueId(), new Running(List.copyOf(tasks), wave, left));

        audit.record(AuditEntry.of("moderation", "spawn.wave")
                .by(by.getUniqueId(), by.getName())
                .in(world.getName())
                .saying(wave.packs().size() + " pack(s), " + wave.total() + " creature(s) at " + centre));
        log.info("{} started a wave of {} at {}.", by.getName(), wave.total(), centre);
        return true;
    }

    /**
     * Stops whatever {@code who} started, leaving what has already arrived where it is.
     *
     * @return how many packs will now never happen
     */
    public int stopWave(UUID who) {
        Running found = running.remove(who);
        if (found == null) {
            return 0;
        }
        int cancelled = 0;
        for (ScheduledTask task : found.tasks()) {
            if (!task.isCancelled()) {
                task.cancel();
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Whether this person has a wave in flight — for the button that says "Stop" instead of "Start". */
    public boolean hasWaveRunning(UUID who) {
        return running.containsKey(who);
    }

    /** How many packs of their wave are still to come. */
    public int packsLeft(UUID who) {
        Running found = running.get(who);
        return found == null ? 0 : Math.max(0, found.packsLeft().get());
    }

    /**
     * Stops everything, for when the module does.
     *
     * <p>A wave outliving its plugin is a wave nothing can stop: the tasks would keep firing against a
     * service that has been stood down, and the only way out would be a restart.
     */
    public int stopEverything() {
        int stopped = 0;
        for (UUID who : List.copyOf(running.keySet())) {
            stopped += stopWave(who) > 0 ? 1 : 0;
        }
        return stopped;
    }

    // ────────────────────────────────────────────────────────────────────────── the seams

    private static Spot spotOf(Location at) {
        return new Spot(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /** Core's {@link Ground}, over one world. */
    private record WorldGround(World world) implements Ground {

        @Override
        public String materialAt(Spot spot) {
            if (!isLoaded(spot)) {
                return null;
            }
            return world.getBlockAt(spot.x(), spot.y(), spot.z()).getType().name();
        }

        @Override
        public boolean set(Spot spot, String material) {
            Material what = Material.matchMaterial(material);
            if (what == null || !isLoaded(spot)) {
                return false;
            }
            Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
            // No physics: a vein is being put *into* stone, and letting gravel above it fall or
            // redstone beside it recalculate is a change nobody asked for at a position nobody chose.
            block.setType(what, false);
            return true;
        }

        @Override
        public boolean isLoaded(Spot spot) {
            return spot.y() >= world.getMinHeight() && spot.y() < world.getMaxHeight()
                    && world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
        }
    }

    /** Core's {@link Spawner}, over one world. */
    private record WorldSpawner(World world) implements Spawner {

        @Override
        public boolean spawn(Spot spot, String type) {
            EntityType what = entityType(type);
            if (what == null || !isLoaded(spot)) {
                return false;
            }
            try {
                Entity spawned = world.spawnEntity(
                        new Location(world, spot.centreX(), spot.y(), spot.centreZ()), what);
                return spawned != null;
            } catch (IllegalArgumentException cannotBeSpawned) {
                // A type the world will not take here — no room, or not spawnable at all. One
                // creature's worth of nothing, which is what Spawns already reports as refused.
                return false;
            }
        }

        @Override
        public boolean isLoaded(Spot spot) {
            return spot.y() >= world.getMinHeight() && spot.y() < world.getMaxHeight()
                    && world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
        }

        private static EntityType entityType(String name) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.ENTITY_TYPE.get(key);
        }
    }

    @Override
    public String describe() {
        return "burying ore veins, and calling up packs and waves of creatures";
    }
}
