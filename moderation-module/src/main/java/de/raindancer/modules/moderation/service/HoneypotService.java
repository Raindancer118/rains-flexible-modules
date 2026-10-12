package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.players.AdminModes;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.rules.BaitVeinRule;
import de.raindancer.modules.moderation.rules.BaitSightRule;
import de.raindancer.modules.moderation.store.BaitSeed;
import de.raindancer.modules.moderation.util.LoadedGrid;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bait ores: a hidden layer of fake ore veins ({@link BaitVeinRule}) sent to each player's client along
 * with the chunk, sealed in rock where no honest eye can see them. An x-ray client renders them like
 * any other ore — the same veins in the same places for everyone, there whenever the chunk is.
 *
 * <p>The server never changes a block. A vein is shown only in rock farther than a sight distance from
 * open space, and the moment a tunnel comes within that distance its real blocks are sent back, so an
 * honest player never sees a diamond that is not there. The distance is one block for a slow pickaxe;
 * an instant one breaks blocks faster than the server can answer, so it grows with dig speed and ping
 * ({@link BaitSightRule}).
 *
 * <p>Because the layer's density is known, the chance that a dig brings a vein within the distance is
 * known too ({@link BaitVeinRule#expected}). That makes "reached N baits" a test with a calculable
 * expectation, not a hunch.
 */
public final class HoneypotService implements IModerationService {

    /** How often veins near the miner are checked against open space something else made. */
    private static final int GUARD_TICKS = 4;
    private static final BaitSightRule SIGHT = new BaitSightRule();
    private static final BaitVeinRule LAYER = new BaitVeinRule();

    /** A position in one world. */
    public record Key(UUID world, int x, int y, int z) {
    }

    /** A vein one client is shown: its blocks and the fake each one shows. */
    private record Shown(Key anchor, Map<Key, Material> blocks) {
    }

    /** One player's veins. */
    private static final class Baits {
        final Map<Key, Shown> veins = new HashMap<>();
        final Map<Key, Shown> byBlock = new HashMap<>();
        /** Veins already turned back because a dig started near them; counted when that dig finishes. */
        final Map<Key, Shown> pending = new LinkedHashMap<>();
        /** How close open space may come before a vein goes; what showing veins and the expectation assume. */
        int distance = 1;
    }

    /** What one finished dig meant for the bait test. */
    public record Reach(int reached, double expected, List<Key> positions) {
        public static final Reach NONE = new Reach(0, 0, List.of());
    }

    private final Plugin plugin;
    private final long seed;
    private final Map<UUID, Baits> byPlayer = new ConcurrentHashMap<>();
    private final Map<Key, Set<UUID>> owners = new ConcurrentHashMap<>();
    private volatile ModerationSettings settings;

    public HoneypotService(Plugin plugin, ModerationSettings settings) {
        this.plugin = plugin;
        this.seed = BaitSeed.load(plugin.getDataFolder().toPath());
        settings(settings);
    }

    /** Keeps a joining player's veins honest on their own thread; the veins themselves come with each chunk. */
    public void start(Player player) {
        Scheduling.entityTimer(plugin, player, GUARD_TICKS, GUARD_TICKS, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            guard(player);
        }, () -> { });
    }

    /**
     * For players already online when the module starts: their chunks arrived before it did. Not at
     * join — Paper has no chunk loader for the player yet and throws from {@code getSentChunkKeys}.
     */
    public void startPlaying(Player player) {
        start(player);
        World world = player.getWorld();
        for (long key : player.getSentChunkKeys()) {
            chunkSent(player, world, (int) key, (int) (key >> 32));
        }
    }

    /**
     * Somebody went into admin mode or came out: every vein they are shown is sent again, as redstone
     * or as ore. Runs on their own thread.
     */
    public void adminModeSwitched(Player player) {
        Baits baits = byPlayer.get(player.getUniqueId());
        // Nothing shown yet — and at join Paper has no chunk loader for them to ask about.
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            {
                for (Shown shown : List.copyOf(baits.veins.values())) {
                    takeBack(player, baits, shown);
                }
                baits.pending.clear();
            }
        }
        World world = player.getWorld();
        for (long key : player.getSentChunkKeys()) {
            chunkSent(player, world, (int) key, (int) (key >> 32));
        }
    }

    private boolean active() {
        ModerationSettings now = settings;
        return now.xrayDetectionEnabled() && now.xrayHoneypots() && now.xrayBaitVeins() > 0;
    }

    /**
     * A chunk just reached the client: show the veins in it — and those of its neighbours reaching into
     * it, once every chunk such a vein touches has arrived, so no vein is ever shown in part.
     */
    public void chunkSent(Player player, World world, int chunkX, int chunkZ) {
        boolean staff = AdminModes.isOn(player.getUniqueId());
        if (!active() || !staff && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        String environment = world.getEnvironment().name();
        if (BaitVeinRule.band(environment, world.getMinHeight()) == null) {
            return;
        }
        Baits baits = byPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new Baits());
        LoadedGrid grid = new LoadedGrid(world);
        Map<Position, BlockData> send = new HashMap<>();
        synchronized (baits) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (BaitVeinRule.Vein vein : LAYER.veinsIn(seed, world.getUID(), environment, world.getMinHeight(),
                            chunkX + dx, chunkZ + dz, settings.xrayBaitVeins())) {
                        Shown shown = show(player, world, grid, baits, vein, chunkX, chunkZ, staff);
                        if (shown != null) {
                            shown.blocks().forEach((key, fake) -> send.put(Position.block(key.x(), key.y(), key.z()),
                                    fake.createBlockData()));
                        }
                    }
                }
            }
        }
        if (!send.isEmpty()) {
            player.sendMultiBlockChange(send);
        }
    }

    private Shown show(Player player, World world, LoadedGrid grid, Baits baits, BaitVeinRule.Vein vein, int chunkX, int chunkZ,
                       boolean staff) {
        Key anchor = new Key(world.getUID(), vein.x(), vein.y(), vein.z());
        if (baits.veins.containsKey(anchor) || baits.pending.containsKey(anchor)) {
            return null;
        }
        boolean inThisChunk = false;
        for (int[] block : vein.blocks()) {
            inThisChunk |= block[0] >> 4 == chunkX && block[2] >> 4 == chunkZ;
            if (!player.isChunkSent(Chunk.getChunkKey(block[0] >> 4, block[2] >> 4))) {
                return null;
            }
        }
        if (!inThisChunk || !LAYER.fits(grid, vein, baits.distance, type -> OreKind.of(type).isPresent())) {
            return null;
        }
        Map<Key, Material> blocks = new LinkedHashMap<>();
        for (int[] block : vein.blocks()) {
            Key key = new Key(world.getUID(), block[0], block[1], block[2]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Two veins touching would read as one odd-shaped one.
                        if (baits.byBlock.containsKey(new Key(key.world(), key.x() + dx, key.y() + dy, key.z() + dz))) {
                            return null;
                        }
                    }
                }
            }
            // Staff in admin mode checking with an x-ray of their own see baits for what they are.
            blocks.put(key, staff ? Material.REDSTONE_BLOCK : BaitVeinRule.fakeFor(vein.kind(), grid.at(block[0], block[1], block[2])));
        }
        Shown shown = new Shown(anchor, blocks);
        baits.veins.put(anchor, shown);
        for (Key key : blocks.keySet()) {
            baits.byBlock.put(key, shown);
            owners.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
        }
        return shown;
    }

    /** Takes a vein off this client: its real blocks go back. Call with the player's baits locked. */
    private void takeBack(Player player, Baits baits, Shown shown) {
        baits.veins.remove(shown.anchor());
        for (Key key : shown.blocks().keySet()) {
            baits.byBlock.remove(key);
            unown(key, player.getUniqueId());
            restore(player, key);
        }
    }

    /**
     * What a finished dig meant for the bait test: the veins it brought within sight distance, and how
     * many chance alone would have. Called while the dug block still stands. Every vein reached is
     * turned back at once and counts once, however many of its blocks came near.
     */
    public Reach dug(Player player, Block block) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null || !active() || AdminModes.isOn(player.getUniqueId())) {
            return Reach.NONE;
        }
        World world = block.getWorld();
        synchronized (baits) {
            int distance = Math.max(baits.distance, sightDistance(player, block));
            if (!ownedAround(world, block.getX(), block.getZ(), 2 * distance + 2 * BaitVeinRule.EXTENT + 1)) {
                return Reach.NONE;
            }
            double expected = LAYER.expected(new LoadedGrid(world), world.getEnvironment().name(), world.getMinHeight(),
                    settings.xrayBaitVeins(), block.getX(), block.getY(), block.getZ(), distance);
            List<Key> reached = new ArrayList<>();
            for (Iterator<Shown> it = baits.veins.values().iterator(); it.hasNext(); ) {
                Shown shown = it.next();
                if (near(shown, block, distance)) {
                    it.remove();
                    for (Key key : shown.blocks().keySet()) {
                        baits.byBlock.remove(key);
                        unown(key, player.getUniqueId());
                        restore(player, key);
                    }
                    reached.add(shown.anchor());
                }
            }
            for (Iterator<Shown> it = baits.pending.values().iterator(); it.hasNext(); ) {
                Shown shown = it.next();
                if (near(shown, block, distance)) {
                    it.remove();
                    reached.add(shown.anchor());
                }
            }
            return new Reach(reached.size(), expected, List.copyOf(reached));
        }
    }

    /**
     * A dig is starting: every vein it could bring within sight distance goes back to rock now,
     * before the client has broken anything next to it. Counted when the dig finishes.
     */
    public void digStarted(Player player, Block block) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            int distance = Math.max(baits.distance, sightDistance(player, block));
            for (Shown shown : List.copyOf(baits.veins.values())) {
                if (near(shown, block, distance)) {
                    takeBack(player, baits, shown);
                    baits.pending.put(shown.anchor(), shown);
                }
            }
            while (baits.pending.size() > 64) {
                baits.pending.remove(baits.pending.keySet().iterator().next());
            }
        }
    }

    private static boolean near(Shown shown, Block block, int distance) {
        for (Key key : shown.blocks().keySet()) {
            if (key.world().equals(block.getWorld().getUID()) && steps(key, block) <= distance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the sight distance up to date with the player's tools and ping, and takes back, uncounted,
     * any vein that open space from elsewhere — another player's tunnel, water, a faster pickaxe picked
     * up — has come near, before the miner gets close enough to break next to it.
     */
    void guard(Player player) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            if (!active()) {
                for (Shown shown : List.copyOf(baits.veins.values())) {
                    takeBack(player, baits, shown);
                }
                return;
            }
            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            Block underfoot = player.getLocation().getBlock().getRelative(0, -1, 0);
            if (Bukkit.isOwnedByCurrentRegion(underfoot.getLocation())) {
                baits.distance = sightDistance(player, underfoot);
            }
            if (baits.veins.isEmpty()) {
                return;
            }
            AttributeInstance range = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double reach = range == null ? 4.5 : range.getValue();
            LoadedGrid grid = new LoadedGrid(world);
            Map<Material, Integer> byRock = new HashMap<>();
            for (Shown shown : List.copyOf(baits.veins.values())) {
                if (!shown.anchor().world().equals(world.getUID())) {
                    continue;
                }
                Key anchor = shown.anchor();
                int distance = baits.distance;
                if (Bukkit.isOwnedByCurrentRegion(world, anchor.x() >> 4, anchor.z() >> 4)) {
                    Block host = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
                    distance = Math.max(distance, byRock.computeIfAbsent(host.getType(), ignored -> sightDistance(player, host)));
                }
                // Out of reach even after the blocks they could break before the server hears of it.
                if (distance(eye, anchor) > reach + distance + BaitVeinRule.EXTENT + 3) {
                    continue;
                }
                boolean clear = true;
                for (Key key : shown.blocks().keySet()) {
                    if (BaitSightRule.openDistance(grid, key.x(), key.y(), key.z(), distance) <= distance) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) {
                    takeBack(player, baits, shown);
                }
            }
        }
    }

    /** Something changed at a block — anybody's break, an explosion, a piston, flowing water. */
    public void changed(Block block) {
        for (int[] side : new int[][]{{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            Key key = new Key(block.getWorld().getUID(), block.getX() + side[0], block.getY() + side[1], block.getZ() + side[2]);
            Set<UUID> holders = owners.get(key);
            if (holders == null) {
                continue;
            }
            for (UUID holder : Set.copyOf(holders)) {
                Baits baits = byPlayer.get(holder);
                Player player = Bukkit.getPlayer(holder);
                if (baits == null || player == null) {
                    unown(key, holder);
                    continue;
                }
                Scheduling.entity(plugin, player, () -> {
                    synchronized (baits) {
                        Shown shown = baits.byBlock.get(key);
                        if (shown != null) {
                            takeBack(player, baits, shown);
                        }
                    }
                });
            }
        }
    }

    /** The client dropped a chunk: its veins went with it, and any part of them in other chunks goes back. */
    public void chunkGone(Player player, World world, int chunkX, int chunkZ) {
        Baits baits = byPlayer.get(player.getUniqueId());
        if (baits == null) {
            return;
        }
        synchronized (baits) {
            for (Shown shown : List.copyOf(baits.veins.values())) {
                boolean touches = false;
                for (Key key : shown.blocks().keySet()) {
                    touches |= key.world().equals(world.getUID()) && key.x() >> 4 == chunkX && key.z() >> 4 == chunkZ;
                }
                if (!touches) {
                    continue;
                }
                baits.veins.remove(shown.anchor());
                for (Key key : shown.blocks().keySet()) {
                    baits.byBlock.remove(key);
                    unown(key, player.getUniqueId());
                    if (key.x() >> 4 != chunkX || key.z() >> 4 != chunkZ) {
                        restore(player, key);
                    }
                }
            }
        }
    }

    /** The fake block this player's client shows at a position, or null. */
    public Material fakeAt(UUID player, UUID world, int x, int y, int z) {
        Baits baits = byPlayer.get(player);
        if (baits == null) {
            return null;
        }
        synchronized (baits) {
            Shown shown = baits.byBlock.get(new Key(world, x, y, z));
            return shown == null ? null : shown.blocks().get(new Key(world, x, y, z));
        }
    }

    /** Fake blocks this player is shown right now. */
    public int count(UUID player) {
        Baits baits = byPlayer.get(player);
        if (baits == null) {
            return 0;
        }
        synchronized (baits) {
            return baits.byBlock.size();
        }
    }

    public void forget(UUID player) {
        Baits baits = byPlayer.remove(player);
        if (baits != null) {
            synchronized (baits) {
                baits.byBlock.keySet().forEach(key -> unown(key, player));
            }
        }
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

    private static double distance(Location center, Key key) {
        double dx = key.x() + 0.5 - center.getX();
        double dy = key.y() + 0.5 - center.getY();
        double dz = key.z() + 0.5 - center.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh == null ? ModerationSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "laying a layer of fake ore veins only an x-ray can see";
    }
}
