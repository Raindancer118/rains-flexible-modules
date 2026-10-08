package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.util.WorldGrid;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.rules.RevealRule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bait ores: fake diamonds and debris sent to one player's client only, sealed inside solid rock
 * where no honest eye can see them. An x-ray client renders them like any other ore.
 *
 * <p>The server never changes a block. Baits are placed only in fully enclosed host rock, and the
 * moment a tunnel comes next to one — the dig starts, or anything else opens a neighbour — the real
 * block is sent back, so an honest player never sees a diamond that is not there.
 *
 * <p>Because baits are scattered uniformly over the enclosed rock around a miner, the chance that
 * any block their digging reveals is a bait is known exactly: baits ÷ enclosed rock. That makes
 * "reached N baits" a test with a calculable expectation, not a hunch.
 */
public final class HoneypotService implements IModerationService {

    private static final int INNER = 6;
    private static final int ATTEMPTS_PER_BAIT = 40;
    /** Extra probes every cycle, only to measure how much enclosed rock is around the miner right now. */
    private static final int PROBES = 64;
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** A position in one world. */
    public record Key(UUID world, int x, int y, int z) {
    }

    /** One player's baits. */
    private static final class Baits {
        final Map<Key, Material> fakes = new HashMap<>();
        /** Baits already turned back because a dig started next to them; counted when that dig reveals them. */
        final java.util.Set<Key> pending = new java.util.HashSet<>();
        Location center;
        double tries;
        double eligible;
        int radius;
    }

    /** What one break's revealed blocks meant for the bait test. */
    public record Reach(int reached, double expected, List<Key> positions) {
        public static final Reach NONE = new Reach(0, 0, List.of());
    }

    private final Plugin plugin;
    private final Map<UUID, Baits> byPlayer = new ConcurrentHashMap<>();
    private final Map<Key, Set<UUID>> owners = new ConcurrentHashMap<>();
    private volatile ModerationSettings settings;

    public HoneypotService(Plugin plugin, ModerationSettings settings) {
        this.plugin = plugin;
        settings(settings);
    }

    /** Keeps a player's baits topped up around them; runs on their own thread every few seconds. */
    public void start(Player player) {
        Scheduling.entityTimer(plugin, player, 100, 100, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            maintain(player);
        }, () -> { });
    }

    void maintain(Player player) {
        ModerationSettings now = settings;
        Baits baits = byPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new Baits());
        synchronized (baits) {
            if (!now.xrayDetectionEnabled() || !now.xrayHoneypots() || !eligible(player)) {
                clear(player, baits, true);
                return;
            }
            Location here = player.getLocation();
            int radius = now.xrayHoneypotRadius();
            baits.radius = radius;
            if (baits.center == null || !here.getWorld().equals(baits.center.getWorld())) {
                clear(player, baits, true);
            }
            baits.center = here.clone();
            World world = here.getWorld();
            // Measured afresh around where they are now: an estimate kept from hours of solid rock
            // would overstate the rock in a cave system and understate the chance of hitting bait.
            baits.tries = 0;
            baits.eligible = 0;
            ThreadLocalRandom probes = ThreadLocalRandom.current();
            for (int probe = 0; probe < PROBES; probe++) {
                Optional<int[]> spot = spotInShell(probes, here.getBlockX(), here.getBlockY(), here.getBlockZ(), radius,
                        world.getMinHeight(), world.getMaxHeight());
                if (spot.isPresent() && !Bukkit.isOwnedByCurrentRegion(world, spot.get()[0] >> 4, spot.get()[2] >> 4)) {
                    continue;
                }
                baits.tries++;
                if (spot.isPresent() && eligibleRock(world, spot.get())) {
                    baits.eligible++;
                }
            }
            for (Iterator<Map.Entry<Key, Material>> it = baits.fakes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Key, Material> bait = it.next();
                double distance = distance(here, bait.getKey());
                // Only ever dropped for being far away: a bait the miner is closing in on is the evidence.
                if (distance > radius + 8) {
                    it.remove();
                    unown(bait.getKey(), player.getUniqueId());
                    restore(player, bait.getKey());
                }
            }
            int missing = now.xrayHoneypotCount() - baits.fakes.size();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int attempt = 0; attempt < missing * ATTEMPTS_PER_BAIT && baits.fakes.size() < now.xrayHoneypotCount(); attempt++) {
                Optional<int[]> found = spotInShell(random, here.getBlockX(), here.getBlockY(), here.getBlockZ(), radius,
                        world.getMinHeight(), world.getMaxHeight());
                if (found.isEmpty() || !Bukkit.isOwnedByCurrentRegion(world, found.get()[0] >> 4, found.get()[2] >> 4)
                        || !eligibleRock(world, found.get())) {
                    continue;
                }
                Key spot = new Key(world.getUID(), found.get()[0], found.get()[1], found.get()[2]);
                Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
                Material fake = fakeFor(world, block);
                if (fake == null || nearOre(world, spot) || nearBait(baits, spot)) {
                    continue;
                }
                baits.fakes.put(spot, fake);
                owners.computeIfAbsent(spot, ignored -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
                player.sendBlockChange(new Location(world, spot.x(), spot.y(), spot.z()), fake.createBlockData());
            }
        }
    }

    /**
     * Which of the blocks a dig just revealed were this player's baits, and how many chance alone
     * would have made baits. Every bait reached is turned back into its real block at once.
     */
    public Reach revealed(Player player, World world, List<int[]> revealedRock) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return Reach.NONE;
        }
        synchronized (baits) {
            if (baits.center == null || baits.fakes.isEmpty() && baits.pending.isEmpty() || !world.equals(baits.center.getWorld())
                    || baits.tries < 10) {
                return Reach.NONE;
            }
            double shell = 4.0 / 3 * Math.PI * (Math.pow(baits.radius, 3) - Math.pow(INNER, 3));
            double enclosedRock = Math.max(1, baits.eligible / baits.tries * shell);
            double perBlock = (baits.fakes.size() + baits.pending.size()) / enclosedRock;
            int inZone = 0;
            List<Key> reached = new ArrayList<>();
            for (int[] block : revealedRock) {
                Key key = new Key(world.getUID(), block[0], block[1], block[2]);
                double distance = distance(baits.center, key);
                if (distance >= INNER && distance <= baits.radius) {
                    inZone++;
                }
                if (baits.fakes.remove(key) != null) {
                    reached.add(key);
                    unown(key, player.getUniqueId());
                    restore(player, key);
                } else if (baits.pending.remove(key)) {
                    reached.add(key);
                }
            }
            return new Reach(reached.size(), inZone * perBlock, List.copyOf(reached));
        }
    }

    /** A dig is starting next to these blocks: any bait among them goes back to rock before it can be seen. */
    public void digStarted(Player player, Block block) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            for (int[] side : SIDES) {
                Key key = new Key(block.getWorld().getUID(), block.getX() + side[0], block.getY() + side[1], block.getZ() + side[2]);
                if (baits.fakes.remove(key) != null) {
                    unown(key, player.getUniqueId());
                    restore(player, key);
                    baits.pending.add(key);
                }
            }
            if (baits.pending.size() > 64) {
                baits.pending.clear();
            }
        }
    }

    /** Something changed at a block — anybody's break, an explosion, a piston, flowing water. */
    public void changed(Block block) {
        for (int[] side : new int[][]{{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            Key key = new Key(block.getWorld().getUID(), block.getX() + side[0], block.getY() + side[1], block.getZ() + side[2]);
            Set<UUID> holders = owners.remove(key);
            if (holders == null) {
                continue;
            }
            for (UUID holder : holders) {
                Baits baits = byPlayer.get(holder);
                Player player = Bukkit.getPlayer(holder);
                if (baits != null) {
                    synchronized (baits) {
                        baits.fakes.remove(key);
                    }
                }
                if (player != null) {
                    Scheduling.entity(plugin, player, () -> restore(player, key));
                }
            }
        }
    }

    /** The client dropped a chunk; its baits went with it. */
    public void chunkGone(Player player, World world, int chunkX, int chunkZ) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            baits.fakes.keySet().removeIf(key -> {
                boolean gone = key.world().equals(world.getUID()) && key.x() >> 4 == chunkX && key.z() >> 4 == chunkZ;
                if (gone) {
                    unown(key, player.getUniqueId());
                }
                return gone;
            });
        }
    }

    /** The fake block this player's client shows at a position, or null. */
    public Material fakeAt(UUID player, UUID world, int x, int y, int z) {
        Baits baits = byPlayer.get(player);
        if (baits == null) {
            return null;
        }
        synchronized (baits) {
            return baits.fakes.get(new Key(world, x, y, z));
        }
    }

    public int count(UUID player) {
        Baits baits = byPlayer.get(player);
        if (baits == null) {
            return 0;
        }
        synchronized (baits) {
            return baits.fakes.size();
        }
    }

    public void forget(UUID player) {
        Baits baits = byPlayer.remove(player);
        if (baits != null) {
            synchronized (baits) {
                baits.fakes.keySet().forEach(key -> unown(key, player));
            }
        }
    }

    private void clear(Player player, Baits baits, boolean tellClient) {
        for (Key key : baits.fakes.keySet()) {
            unown(key, player.getUniqueId());
            if (tellClient) {
                restore(player, key);
            }
        }
        baits.fakes.clear();
    }

    private void unown(Key key, UUID player) {
        owners.computeIfPresent(key, (ignored, holders) -> {
            holders.remove(player);
            return holders.isEmpty() ? null : holders;
        });
    }

    private void restore(Player player, Key key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !player.getWorld().equals(world)) {
            return;
        }
        Location at = new Location(world, key.x(), key.y(), key.z());
        if (Bukkit.isOwnedByCurrentRegion(at)) {
            player.sendBlockChange(at, world.getBlockAt(at).getBlockData());
        } else {
            // Folia: the block belongs to another region; read it there, never guess.
            Scheduling.region(plugin, at, () -> player.sendBlockChange(at, world.getBlockAt(at).getBlockData()));
        }
    }

    /** Under cover in the overworld below sea level, anywhere in the nether — where x-ray pays. */
    private static boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return false;
        }
        World world = player.getWorld();
        return switch (world.getEnvironment()) {
            case NORMAL -> player.getLocation().getY() < 50 && player.getLocation().getBlock().getLightFromSky() < 4;
            case NETHER -> player.getLocation().getY() < 120;
            default -> false;
        };
    }

    /** What would tempt an x-ray here: diamonds deep down, gold higher up, debris in the nether. */
    static Material fakeFor(World world, Block host) {
        int y = host.getY();
        boolean deepslate = host.getType() == Material.DEEPSLATE || host.getType() == Material.TUFF
                || host.getType() == Material.COBBLED_DEEPSLATE;
        return switch (world.getEnvironment()) {
            case NORMAL -> y < 16 ? (deepslate ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE)
                    : y < 40 ? (deepslate ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE) : null;
            case NETHER -> y >= 8 && y <= 119 && (host.getType() == Material.NETHERRACK || host.getType() == Material.BASALT
                    || host.getType() == Material.BLACKSTONE) ? Material.ANCIENT_DEBRIS : null;
            default -> null;
        };
    }

    private static boolean nearOre(World world, Key spot) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (OreKind.of(world.getBlockAt(spot.x() + dx, spot.y() + dy, spot.z() + dz).getType()).isPresent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean nearBait(Baits baits, Key spot) {
        for (Key other : baits.fakes.keySet()) {
            if (Math.abs(other.x() - spot.x()) <= 3 && Math.abs(other.y() - spot.y()) <= 3 && Math.abs(other.z() - spot.z()) <= 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * A spot uniform over the shell between {@link #INNER} and {@code radius} — or nothing, when it
     * falls outside the world. Never moved to the edge: that would pile bait into the bottom layers,
     * where honest players dig for diamonds, and break the uniform spread the expectation relies on.
     */
    static Optional<int[]> spotInShell(java.util.Random random, int x, int y, int z, int radius, int minY, int maxY) {
        while (true) {
            int dx = random.nextInt(2 * radius + 1) - radius;
            int dy = random.nextInt(2 * radius + 1) - radius;
            int dz = random.nextInt(2 * radius + 1) - radius;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < INNER || distance > radius) {
                continue;
            }
            int at = y + dy;
            if (at <= minY || at >= maxY - 1) {
                return Optional.empty();
            }
            return Optional.of(new int[]{x + dx, at, z + dz});
        }
    }

    private static boolean eligibleRock(World world, int[] spot) {
        if (!world.isChunkLoaded(spot[0] >> 4, spot[2] >> 4)) {
            return false;
        }
        return OreKind.isHostRock(world.getBlockAt(spot[0], spot[1], spot[2]).getType())
                && RevealRule.enclosed(new WorldGrid(world), spot[0], spot[1], spot[2]);
    }

    private static double distance(Location center, Key key) {
        double dx = key.x() + 0.5 - center.getX();
        double dy = key.y() + 0.5 - center.getY();
        double dz = key.z() + 0.5 - center.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh == null ? ModerationSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "showing each miner fake ores only an x-ray can see";
    }
}
