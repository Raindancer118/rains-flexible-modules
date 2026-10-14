package de.raindancer.modules.enchants.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.rules.PortalFrame;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Portals to the shard world: an ordinary obsidian frame, opened with the portal key instead of fire. The
 * game sees a valid nether portal (so the blocks stay), and which portals lead to the shard world is written
 * on their chunks. Any portal in the shard world leads back to where the player came from.
 */
public final class ShardPortalService {

    private static final NamespacedKey PORTALS = new NamespacedKey("rainsenchants", "shard_portals");
    private static final NamespacedKey RETURN = new NamespacedKey("rainsenchants", "shard_return");
    /** Ticks before the next portal works on an arrival, so nobody bounces straight back. */
    private static final int COOLDOWN_TICKS = 100;
    private static final double INSIDE_BORDER = 0.9;

    private final Plugin plugin;
    private final Land land;
    private final ShardWorldService worlds;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Messages messages;
    private final Effects effects;

    public ShardPortalService(Plugin plugin, Land land, ShardWorldService worlds, Supplier<EnchantsSettings> settings,
                              EnchantAdvancements advancements, Messages messages, Effects effects) {
        this.plugin = plugin;
        this.land = land;
        this.worlds = worlds;
        this.settings = settings;
        this.advancements = advancements;
        this.messages = messages;
        this.effects = effects;
    }

    /**
     * Whether {@code player} may open a portal filling {@code blocks}: wherever they may build. Opening one places
     * blocks and sets nothing alight, so whoever is trusted on a claim may — fire stays the owner's to allow.
     */
    static boolean mayOpen(Land land, Player player, World world, java.util.List<int[]> blocks) {
        for (int[] at : blocks) {
            if (!land.can(player, new Location(world, at[0], at[1], at[2]), LandAction.BUILD)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A right click with the portal key on obsidian. @return true if it opened a portal (the click is used up)
     */
    public boolean ignite(Player player, Block clicked, BlockFace face, ItemStack held) {
        EnchantsSettings config = settings.get();
        if (!config.shardWorldEnabled() || held == null || held.getType() != config.portalKey()
                || clicked.getType() != Material.OBSIDIAN || worlds.world().isEmpty()) {
            return false;
        }
        Block start = clicked.getRelative(face);
        World world = clicked.getWorld();
        PortalFrame.Grid grid = new PortalFrame.Grid() {
            @Override
            public boolean frame(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType() == Material.OBSIDIAN;
            }

            @Override
            public boolean open(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType().isAir();
            }
        };
        Optional<PortalFrame.Inside> inside = PortalFrame.find(grid, start.getX(), start.getY(), start.getZ());
        if (inside.isEmpty()) {
            return false;
        }
        if (!mayOpen(land, player, world, inside.get().blocks())) {
            messages.send(player, "enchants.portal-denied");
            effects.play(player.getUniqueId(), Cues.NO);
            return true;
        }
        for (int[] at : inside.get().blocks()) {
            Block block = world.getBlockAt(at[0], at[1], at[2]);
            Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
            portal.setAxis(inside.get().alongX() ? Axis.X : Axis.Z);
            block.setBlockData(portal, false);
            mark(block, true);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        world.playSound(start.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.4f);
        messages.send(player, "enchants.portal-opened");
        return true;
    }

    private static String spot(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static Set<String> marks(Chunk chunk) {
        String stored = chunk.getPersistentDataContainer().get(PORTALS, PersistentDataType.STRING);
        return stored == null || stored.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(stored.split(";")));
    }

    private static void mark(Block block, boolean on) {
        Chunk chunk = block.getChunk();
        Set<String> marks = marks(chunk);
        boolean changed = on ? marks.add(spot(block)) : marks.remove(spot(block));
        if (changed) {
            if (marks.isEmpty()) {
                chunk.getPersistentDataContainer().remove(PORTALS);
            } else {
                chunk.getPersistentDataContainer().set(PORTALS, PersistentDataType.STRING, String.join(";", marks));
            }
        }
    }

    /** Lit some other way (fire): no longer ours. */
    public void forget(Block block) {
        mark(block, false);
    }

    /** A player's hitbox: half its width each way, and its height. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** Whether somebody standing at {@code at} touches one of our portals — anywhere their hitbox does, as the game asks. */
    public boolean inOurPortal(Location at) {
        World world = at.getWorld();
        for (int[] spot : PortalFrame.touched(at.getX(), at.getY(), at.getZ(), HALF_WIDTH, HEIGHT)) {
            Block block = world.getBlockAt(spot[0], spot[1], spot[2]);
            if (block.getType() == Material.NETHER_PORTAL && marks(block.getChunk()).contains(spot(block))) {
                return true;
            }
        }
        return false;
    }

    /** @return true if this portal trip is ours to make (the game's own is then cancelled) */
    public boolean travel(Player player, Location from) {
        if (worlds.isShardWorld(from.getWorld())) {
            back(player);
            return true;
        }
        if (!inOurPortal(from)) {
            return false;
        }
        Optional<World> target = worlds.world();
        if (target.isEmpty()) {
            return false;
        }
        player.getPersistentDataContainer().set(RETURN, PersistentDataType.STRING, write(from));
        double limit = settings.get().shardWorldRadius() * INSIDE_BORDER;
        int x = (int) Math.max(-limit, Math.min(limit, from.getX()));
        int z = (int) Math.max(-limit, Math.min(limit, from.getZ()));
        World world = target.get();
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Scheduling.region(plugin, new Location(world, x, 0, z), () -> {
            Location arrival = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5,
                    from.getYaw(), 0);
            Scheduling.entity(plugin, player, () -> player.teleportAsync(arrival).thenAccept(done -> {
                if (done) {
                    Scheduling.entity(plugin, player, () -> {
                        player.setPortalCooldown(COOLDOWN_TICKS);
                        messages.send(player, "enchants.portal-arrived");
                        effects.play(player.getUniqueId(), Cues.TELEPORT);
                        advancements.enteredShardWorld(player.getUniqueId());
                    });
                }
            }));
        }));
        return true;
    }

    private void back(Player player) {
        String stored = player.getPersistentDataContainer().get(RETURN, PersistentDataType.STRING);
        Location home = read(stored).orElseGet(() -> Bukkit.getWorlds().get(0).getSpawnLocation());
        player.teleportAsync(home).thenAccept(done -> {
            if (done) {
                Scheduling.entity(plugin, player, () -> player.setPortalCooldown(COOLDOWN_TICKS));
            }
        });
    }

    private static String write(Location at) {
        return at.getWorld().getUID() + ";" + at.getX() + ";" + at.getY() + ";" + at.getZ() + ";" + at.getYaw();
    }

    private static Optional<Location> read(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(";");
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            return world == null ? Optional.empty() : Optional.of(new Location(world, Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), 0));
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }
}
