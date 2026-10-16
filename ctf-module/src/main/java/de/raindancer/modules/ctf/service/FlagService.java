package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.world.blocks.Rewind;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Flag;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Round;
import de.raindancer.modules.ctf.model.Trail;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a flag looks like where it is: a banner on its stand with its name over it at home, on its carrier's head and in
 * their hand while carried, turning in the air where it lies when dropped, flying when thrown. Nothing here decides — the
 * round says where each flag is, this only shows it.
 *
 * <p>Every entity it makes is not saved with its chunk, and every block it sets is told to the round's {@link Rewind}
 * first, so nothing is left behind by a crash or by the end of a round.
 */
public final class FlagService implements ICtfService {

    /** Marks a flag item: in a carrier's hand, on their head, or flying. The value is the flag's side. */
    public static final String FLAG_KEY = "flag";

    private final Plugin plugin;
    private final Effects effects;
    private final NamespacedKey flagKey;
    private final Map<Integer, List<UUID>> shown = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> flying = new ConcurrentHashMap<>();
    private volatile Rewind rewind;
    private volatile CtfSettings settings;

    public FlagService(Plugin plugin, Effects effects, CtfSettings settings) {
        this.plugin = plugin;
        this.effects = effects;
        this.settings = settings;
        this.flagKey = new NamespacedKey(plugin, FLAG_KEY);
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public void rewind(Rewind rewind) {
        this.rewind = rewind;
    }

    public static Material bannerOf(Round round, Flag flag) {
        return flag.isNeutral() ? Material.WHITE_BANNER : round.side(flag.side()).colour().bannerMaterial();
    }

    public static String nameOf(Round round, Flag flag) {
        return flag.isNeutral() ? "<white>Neutral flag</white>" : round.side(flag.side()).tagged() + " flag";
    }

    /** The flag item for a side: a banner marked as the flag, which cannot be dropped, moved or placed. */
    public ItemStack item(Round round, Flag flag) {
        ItemStack banner = new ItemStack(bannerOf(round, flag));
        banner.editMeta(meta -> {
            meta.itemName(MiniMessage.miniMessage().deserialize(nameOf(round, flag)
                    + (settings.throwing() ? " <gray>(right click: throw)" : "")));
            meta.getPersistentDataContainer().set(flagKey, PersistentDataType.INTEGER, flag.side());
            meta.setEnchantmentGlintOverride(true);
        });
        return banner;
    }

    public boolean isFlagItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(flagKey, PersistentDataType.INTEGER);
    }

    public Optional<Integer> flagSideOf(Entity entity) {
        Integer side = flying.get(entity.getUniqueId());
        return Optional.ofNullable(side);
    }

    // ------------------------------------------------------------------ at home and lying

    /** Puts the flag where the round says it is: at home on its stand, or lying where it fell. Global thread. */
    public void show(Round round, Flag flag) {
        hide(flag.side());
        World world = Bukkit.getWorld(round.world());
        if (world == null || flag.state() == Flag.State.CARRIED) {
            return;
        }
        Point at = flag.state() == Flag.State.DROPPED ? flag.lying().orElse(flag.home()) : flag.home();
        Location spot = new Location(world, at.x(), at.y(), at.z());
        Material banner = bannerOf(round, flag);
        Component label = MiniMessage.miniMessage().deserialize(flag.state() == Flag.State.HOME
                ? nameOf(round, flag) : nameOf(round, flag) + " <gray>— dropped");
        Scheduling.region(plugin, spot, () -> {
            List<UUID> ids = new ArrayList<>();
            if (flag.state() == Flag.State.HOME) {
                Block stand = spot.getBlock();
                if (stand.getType().isAir() || stand.getType().name().endsWith("_BANNER")) {
                    if (rewind != null) {
                        rewind.remember(stand);
                    }
                    stand.setType(banner, false);
                    if (stand.getBlockData() instanceof Rotatable rotatable) {
                        rotatable.setRotation(facing(at.yaw()));
                        stand.setBlockData(rotatable, false);
                    }
                } else {
                    ids.add(floating(spot.clone().add(0, 0.8, 0), banner).getUniqueId());
                }
            } else {
                ids.add(floating(spot.clone().add(0, 0.6, 0), banner).getUniqueId());
            }
            ids.add(label(spot.clone().add(0, 2.4, 0), label).getUniqueId());
            shown.put(flag.side(), ids);
        });
    }

    /** Takes down what shows a flag (its stand's banner too). */
    public void hide(Round round, Flag flag) {
        hide(flag.side());
        World world = Bukkit.getWorld(round.world());
        if (world == null) {
            return;
        }
        Point home = flag.home();
        Location spot = new Location(world, home.x(), home.y(), home.z());
        Scheduling.region(plugin, spot, () -> {
            Block stand = spot.getBlock();
            if (stand.getType().name().endsWith("_BANNER")) {
                stand.setType(Material.AIR, false);
            }
        });
    }

    private void hide(int side) {
        List<UUID> ids = shown.remove(side);
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                Scheduling.entity(plugin, entity, entity::remove);
            }
        }
    }

    /** Everything taken down at the end of a round. */
    public void clear(Round round) {
        for (Flag flag : round.flags()) {
            hide(round, flag);
        }
        shown.keySet().forEach(this::hide);
        for (UUID id : List.copyOf(flying.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                Scheduling.entity(plugin, entity, entity::remove);
            }
        }
        flying.clear();
    }

    private ItemDisplay floating(Location at, Material banner) {
        return at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(banner));
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setPersistent(false);
            display.setGlowing(true);
        });
    }

    private TextDisplay label(Location at, Component text) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setPersistent(false);
        });
    }

    private static org.bukkit.block.BlockFace facing(float yaw) {
        org.bukkit.block.BlockFace[] faces = {
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.SOUTH_SOUTH_WEST,
                org.bukkit.block.BlockFace.SOUTH_WEST, org.bukkit.block.BlockFace.WEST_SOUTH_WEST,
                org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.WEST_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH_WEST, org.bukkit.block.BlockFace.NORTH_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.NORTH_NORTH_EAST,
                org.bukkit.block.BlockFace.NORTH_EAST, org.bukkit.block.BlockFace.EAST_NORTH_EAST,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.EAST_SOUTH_EAST,
                org.bukkit.block.BlockFace.SOUTH_EAST, org.bukkit.block.BlockFace.SOUTH_SOUTH_EAST};
        int index = Math.floorMod(Math.round(yaw / 22.5f), 16);
        return faces[index];
    }

    // ------------------------------------------------------------------ carried

    /** The carrier wears the flag and holds it. Their own thread. */
    public void carry(Player carrier, Round round, Flag flag) {
        ItemStack item = item(round, flag);
        carrier.getInventory().setItem(EquipmentSlot.HEAD, item.clone());
        carrier.getInventory().setItem(8, item);
        carrier.getInventory().setHeldItemSlot(8);
    }

    /** The flag taken off a carrier, and their own helmet back. Their own thread. */
    public void uncarry(Player carrier, ItemStack helmet) {
        // The head first: the sweep below reaches the armour slots too, and would leave the head bare.
        if (isFlagItem(carrier.getInventory().getItem(EquipmentSlot.HEAD))) {
            carrier.getInventory().setItem(EquipmentSlot.HEAD, helmet);
        }
        for (int slot = 0; slot < carrier.getInventory().getSize(); slot++) {
            if (isFlagItem(carrier.getInventory().getItem(slot))) {
                carrier.getInventory().setItem(slot, null);
            }
        }
        carrier.setGlowing(false);
    }

    /** The trail a carrier leaves, at their feet. */
    public void trail(Player carrier, Trail trail, TeamColour colour) {
        Location at = carrier.getLocation().add(0, 0.3, 0);
        Integer rgb = trail == Trail.TEAM ? colour.armourColour().asRGB() : null;
        ParticleCue cue = new ParticleCue(trail.particle(), trail == Trail.TOTEM ? 6 : 3, 0.2, 0.1, 0.2, 0.01, rgb);
        effects.playAt(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), Effect.of(cue));
    }

    // ------------------------------------------------------------------ thrown

    /** Gravity and air drag a thrown flag feels each tick — an item's. */
    static final double GRAVITY = 0.04;
    static final double DRAG = 0.98;
    /** How long a flag may fly before it is put down where it is. */
    static final int MOST_TICKS = 120;

    /**
     * Throws a carried flag where the carrier looks. It flies as a display moved a tick at a time — no item entity, so
     * no protection plugin can swallow it and nobody can pick it up the ordinary way — until it hits the ground. A wall
     * stops it going on but not falling. Carrier's own thread.
     *
     * @param landed told on the global thread where it came to rest, or null when it fell off the map
     */
    public void toss(Player carrier, Round round, Flag flag, double power, java.util.function.Consumer<Point> landed) {
        Location start = carrier.getEyeLocation();
        fly(start, start.getDirection().normalize().multiply(power).add(new Vector(0, 0.2, 0)), bannerOf(round, flag),
                round.floorY(), flag.side(), landed);
    }

    /**
     * A banner flying from {@code start} — a thrown flag, a practice throw in the lobby. Its own thread: the thrower's.
     *
     * @param side   the flag's side, for {@link #flagSideOf}
     * @param landed told on the global thread where it came to rest, or null when it fell below {@code floorY}
     */
    public void fly(Location start, Vector velocity, Material banner, double floorY, int side,
                    java.util.function.Consumer<Point> landed) {
        ItemDisplay flying = start.getWorld().spawn(start, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(banner));
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setPersistent(false);
            display.setGlowing(true);
            display.setTeleportDuration(1);
        });
        this.flying.put(flying.getUniqueId(), side);
        double[] at = {start.getX(), start.getY(), start.getZ()};
        double[] speed = {velocity.getX(), velocity.getY(), velocity.getZ()};
        int[] ticks = {0};
        boolean[] done = {false};
        java.util.function.Consumer<Point> finish = where -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            this.flying.remove(flying.getUniqueId());
            Scheduling.global(plugin, () -> landed.accept(where));
        };
        // A region timer at the start, not the display's own scheduler: a display is not ticked, so its scheduler never
        // runs (found live — every thrown flag hung in the air for good).
        Scheduling.regionTimer(plugin, start, 1L, 1L, task -> {
            if (!flying.isValid()) {
                task.cancel();
                finish.accept(new Point(at[0], Math.floor(at[1]), at[2], 0f, 0f));
                return;
            }
            ticks[0]++;
            Step step = step(at, speed, (x, y, z) -> solid(flying.getWorld(), x, y, z));
            if (step.landed() || ticks[0] >= MOST_TICKS || at[1] < floorY) {
                task.cancel();
                flying.remove();
                finish.accept(at[1] < floorY ? null : new Point(at[0], Math.floor(at[1]), at[2], 0f, 0f));
                return;
            }
            flying.teleport(new Location(flying.getWorld(), at[0], at[1], at[2]));
        });
    }

    /** Whether a block is something a flag lands on or bounces off. */
    interface Solid {
        boolean at(double x, double y, double z);
    }

    /** One tick of flight: whether it came to rest. */
    record Step(boolean landed) {
    }

    /**
     * Moves a flying flag one tick: drag, gravity, then the move — blocked sideways by a wall, put down on a floor.
     * Changes {@code at} and {@code speed} in place.
     */
    static Step step(double[] at, double[] speed, Solid solid) {
        speed[0] *= DRAG;
        speed[2] *= DRAG;
        speed[1] = (speed[1] - GRAVITY) * DRAG;
        double nextX = at[0] + speed[0];
        double nextZ = at[2] + speed[2];
        if (solid.at(nextX, at[1], nextZ)) {
            speed[0] = 0;
            speed[2] = 0;
            nextX = at[0];
            nextZ = at[2];
        }
        double nextY = at[1] + speed[1];
        if (speed[1] < 0 && solid.at(nextX, nextY, nextZ)) {
            at[0] = nextX;
            at[1] = Math.floor(nextY) + 1;
            at[2] = nextZ;
            return new Step(true);
        }
        at[0] = nextX;
        at[1] = nextY;
        at[2] = nextZ;
        return new Step(false);
    }

    private static boolean solid(World world, double x, double y, double z) {
        Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return !block.isPassable();
    }
}
