package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.util.WorldGrid;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.rules.BaitSightRule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

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
 * <p>The server never changes a block. Baits are placed only in host rock at least a sight distance
 * away from open space, and the moment a tunnel comes within that distance the real block is sent
 * back, so an honest player never sees a diamond that is not there. The distance is one block for a
 * slow pickaxe; an instant one breaks blocks faster than the server can answer, so it grows with
 * dig speed and ping ({@link BaitSightRule}).
 *
 * <p>Because baits are scattered uniformly over that rock around a miner, the chance that a dig
 * brings a bait within the distance is known exactly: baits ÷ eligible rock, for every block the dig
 * newly brings that near. That makes "reached N baits" a test with a calculable expectation, not a hunch.
 */
public final class HoneypotService implements IModerationService {

    private static final int INNER = 6;
    private static final int ATTEMPTS_PER_BAIT = 40;
    /** Extra probes every cycle, only to measure how much enclosed rock is around the miner right now. */
    private static final int PROBES = 64;
    /** How often baits near the miner are checked against open space something else made. */
    private static final int GUARD_TICKS = 4;
    private static final BaitSightRule SIGHT = new BaitSightRule();

    /** A position in one world. */
    public record Key(UUID world, int x, int y, int z) {
    }

    /** One player's baits. */
    private static final class Baits {
        final Map<Key, Material> fakes = new HashMap<>();
        /** Baits already turned back because a dig started near them; counted when that dig finishes. */
        final java.util.Set<Key> pending = new java.util.HashSet<>();
        Location center;
        double tries;
        double eligible;
        int radius;
        /** How close open space may come before a bait goes; what placement and the expectation assume. */
        int distance = 1;
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
        Scheduling.entityTimer(plugin, player, 100 + GUARD_TICKS, GUARD_TICKS, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            guard(player);
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
            baits.distance = sightDistance(player, here.getBlock().getRelative(0, -1, 0));
            int sight = baits.distance;
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
                if (spot.isPresent() && eligibleRock(world, spot.get(), sight)) {
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
            baits.pending.removeIf(key -> distance(here, key) > radius + 8);
            int missing = now.xrayHoneypotCount() - baits.fakes.size();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int attempt = 0; attempt < missing * ATTEMPTS_PER_BAIT && baits.fakes.size() < now.xrayHoneypotCount(); attempt++) {
                Optional<int[]> found = spotInShell(random, here.getBlockX(), here.getBlockY(), here.getBlockZ(), radius,
                        world.getMinHeight(), world.getMaxHeight());
                if (found.isEmpty() || !Bukkit.isOwnedByCurrentRegion(world, found.get()[0] >> 4, found.get()[2] >> 4)
                        || !eligibleRock(world, found.get(), sight)) {
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
     * What a finished dig meant for the bait test: the baits it brought within sight distance, and how
     * many chance alone would have — one bait's worth for every eligible block it newly brought that
     * near. Called while the dug block still stands. Every bait reached is turned back at once.
     */
    public Reach dug(Player player, Block block) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return Reach.NONE;
        }
        World world = block.getWorld();
        synchronized (baits) {
            if (baits.center == null || baits.fakes.isEmpty() && baits.pending.isEmpty() || !world.equals(baits.center.getWorld())
                    || baits.tries < 10) {
                return Reach.NONE;
            }
            int distance = Math.max(baits.distance, sightDistance(player, block));
            if (!ownedAround(world, block.getX(), block.getZ(), 2 * distance + 1)) {
                return Reach.NONE;
            }
            double shell = 4.0 / 3 * Math.PI * (Math.pow(baits.radius, 3) - Math.pow(INNER, 3));
            double eligibleRock = Math.max(1, baits.eligible / baits.tries * shell);
            double perBlock = (baits.fakes.size() + baits.pending.size()) / eligibleRock;
            int inZone = 0;
            for (int[] near : SIGHT.newlyNear(new WorldGrid(world), block.getX(), block.getY(), block.getZ(), distance)) {
                Key key = new Key(world.getUID(), near[0], near[1], near[2]);
                double fromCenter = distance(baits.center, key);
                if (fromCenter >= INNER && fromCenter <= baits.radius
                        && OreKind.isHostRock(world.getBlockAt(near[0], near[1], near[2]).getType())) {
                    inZone++;
                }
            }
            List<Key> reached = new ArrayList<>();
            for (Iterator<Key> it = baits.fakes.keySet().iterator(); it.hasNext(); ) {
                Key key = it.next();
                if (steps(key, block) <= distance) {
                    it.remove();
                    unown(key, player.getUniqueId());
                    restore(player, key);
                    reached.add(key);
                }
            }
            for (Iterator<Key> it = baits.pending.iterator(); it.hasNext(); ) {
                Key key = it.next();
                if (steps(key, block) <= distance) {
                    it.remove();
                    reached.add(key);
                }
            }
            return new Reach(reached.size(), inZone * perBlock, List.copyOf(reached));
        }
    }

    /**
     * A dig is starting: every bait it could bring within sight distance goes back to rock now,
     * before the client has broken anything next to it. Counted when the dig finishes.
     */
    public void digStarted(Player player, Block block) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            int distance = Math.max(baits.distance, sightDistance(player, block));
            for (Iterator<Key> it = baits.fakes.keySet().iterator(); it.hasNext(); ) {
                Key key = it.next();
                if (key.world().equals(block.getWorld().getUID()) && steps(key, block) <= distance) {
                    it.remove();
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

    /**
     * Open space something else made — another player's tunnel, water, a faster pickaxe picked up —
     * can put a bait within sight distance without this player digging. Such a bait goes back
     * uncounted, before the miner gets close enough to break next to it.
     */
    void guard(Player player) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            if (baits.fakes.isEmpty()) {
                return;
            }
            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            AttributeInstance range = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double reach = range == null ? 4.5 : range.getValue();
            WorldGrid grid = new WorldGrid(world);
            Map<Material, Integer> byRock = new HashMap<>();
            for (Iterator<Map.Entry<Key, Material>> it = baits.fakes.entrySet().iterator(); it.hasNext(); ) {
                Key key = it.next().getKey();
                if (!key.world().equals(world.getUID())) {
                    continue;
                }
                Block host = null;
                int distance = baits.distance;
                boolean owned = ownedAround(world, key.x(), key.z(), BaitSightRule.MAX_DISTANCE);
                if (owned) {
                    host = world.getBlockAt(key.x(), key.y(), key.z());
                    Block rock = host;
                    distance = Math.max(distance, byRock.computeIfAbsent(host.getType(), ignored -> sightDistance(player, rock)));
                }
                // Out of reach even after the blocks they could break before the server hears of it.
                if (distance(eye, key) > reach + distance + 3) {
                    continue;
                }
                if (owned && BaitSightRule.openDistance(grid, key.x(), key.y(), key.z(), distance) > distance) {
                    continue;
                }
                it.remove();
                unown(key, player.getUniqueId());
                restore(player, key);
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

    /** Host rock with no open space within the sight distance — the only rock a bait may sit in. */
    private static boolean eligibleRock(World world, int[] spot, int distance) {
        if (!world.isChunkLoaded(spot[0] >> 4, spot[2] >> 4) || !ownedAround(world, spot[0], spot[2], distance)) {
            return false;
        }
        WorldGrid grid = new WorldGrid(world);
        return OreKind.isHostRock(world.getBlockAt(spot[0], spot[1], spot[2]).getType())
                && BaitSightRule.openDistance(grid, spot[0], spot[1], spot[2], distance) > distance;
    }

    /** Folia: every chunk a read around a spot may touch must be this thread's. */
    private static boolean ownedAround(World world, int x, int z, int reach) {
        return Bukkit.isOwnedByCurrentRegion(world, (x - reach) >> 4, (z - reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (x + reach) >> 4, (z + reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (x - reach) >> 4, (z + reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (x + reach) >> 4, (z - reach) >> 4);
    }

    private static int steps(Key key, Block block) {
        return Math.abs(key.x() - block.getX()) + Math.abs(key.y() - block.getY()) + Math.abs(key.z() - block.getZ());
    }

    /**
     * The sight distance for this player digging this kind of block, with the fastest tool in their
     * hotbar or off hand: swapping to it is instant, and the server would hear of it too late.
     */
    static int sightDistance(Player player, Block block) {
        BlockData data = block.getBlockData();
        float hardness = block.getType().getHardness();
        PotionEffect haste = player.getPotionEffect(PotionEffectType.HASTE);
        PotionEffect conduit = player.getPotionEffect(PotionEffectType.CONDUIT_POWER);
        int hasteLevel = Math.max(haste == null ? 0 : haste.getAmplifier() + 1, conduit == null ? 0 : conduit.getAmplifier() + 1);
        AttributeInstance breakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        double attribute = breakSpeed == null ? 1 : breakSpeed.getValue();
        PlayerInventory inventory = player.getInventory();
        double best = 0;
        for (int slot = 0; slot <= 9; slot++) {
            ItemStack item = slot == 9 ? inventory.getItemInOffHand() : inventory.getItem(slot);
            if (item == null) {
                item = ItemStack.empty();
            }
            float base = block.getDestroySpeed(item, false);
            // Efficiency only helps a tool that suits the block, as in vanilla; taken the larger way in
            // case the API already counts it.
            double tool = Math.max(block.getDestroySpeed(item, true),
                    base > 1 && item.getEnchantmentLevel(Enchantment.EFFICIENCY) > 0
                            ? base + Math.pow(item.getEnchantmentLevel(Enchantment.EFFICIENCY), 2) + 1 : base);
            boolean canHarvest = !data.requiresCorrectToolForDrops() || data.isPreferredTool(item);
            best = Math.max(best, BaitSightRule.progress(tool, hasteLevel, attribute, hardness, canHarvest));
        }
        return SIGHT.distance(best, player.getPing());
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
