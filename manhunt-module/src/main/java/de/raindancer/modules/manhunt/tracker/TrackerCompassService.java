package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Candidate;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Following;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tracking compass' Bukkit half: hands one to every Hunter when a hunt starts, re-aims all of
 * them on a timer, and takes them back when the hunt is over.
 *
 * <h2>Where the deciding happens</h2>
 * Nowhere here — see {@link TrackerCompass}. This class turns live {@code Location}s into
 * {@link Point}s, asks, and writes the answer into an {@link ItemStack}. That split is what makes
 * "which Runner, and where does the needle go" testable without a server.
 *
 * <h2>Where the needle comes from</h2>
 * In the overworld, from {@link Player#setCompassTarget}: per player, sent to that one client, and
 * never part of the item — so the needle follows every step without the compass ever being redrawn.
 * In the Nether and the End, where a plain compass only spins, from a lodestone set with
 * {@link CompassMeta#setLodestoneTracked(boolean) tracked = false} (a tracked one insists on a real
 * lodestone block at the target), rewritten only when the target leaves a block. See
 * {@link #needleFromCompassTarget}. The distance is on the action bar, not in the lore, for the same
 * reason.
 *
 * <h2>Why the timer restarts on a settings change</h2>
 * A Paper repeating task's period is fixed when it is scheduled. Rather than run every tick and skip
 * most of them — paying for a hundred wake-ups to use ten — the timer is cancelled and re-armed when
 * the configured interval actually changes, which is a thing that happens once in a menu click, not
 * once a tick.
 *
 * <h2>Thread notes</h2>
 * The sweep reads every Runner's position from the global region thread, the same way
 * {@code ManhuntService}'s own clock tick and {@code ChaosService} already read the whole roster;
 * each Hunter's inventory is then written on that Hunter's own entity scheduler, so the actual item
 * mutation is always on the thread owning them even under Folia.
 */
public final class TrackerCompassService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String TAG = "tracker";

    private final Plugin plugin;
    /** The hunt in progress, or empty between hunts — held by the mode, never copied here. */
    private final java.util.function.Supplier<Optional<Hunt>> liveHunt;
    private final TrackerCompass compass;
    private final PortalMemory portals;
    private final Messages messages;
    private final ActionBars actionBars;
    private final NamespacedKey marker;

    /** The action bar slot the distance is shown in — its own, so it never takes turns with the clock. */
    static final String DISTANCE_OWNER = "manhunt-tracker";

    /** Hunters currently being shown a distance, so the slot is cleared once and not every sweep. */
    private final java.util.Set<UUID> showingDistance = ConcurrentHashMap.newKeySet();

    /**
     * What each Hunter has set their own compass to. Never a {@code Player} — see
     * {@link PortalMemory}. An absent entry is a Hunter who has never right-clicked it, which is not
     * the same as one who cycled back to {@link Following#NEAREST}: the first takes whatever
     * {@code tracker-targets} says, the second has said it themselves and outranks it.
     */
    private final Map<UUID, Following> picks = new ConcurrentHashMap<>();

    /** The compass target each Hunter was last sent — see {@link CompassTargets}. */
    private final CompassTargets compassTargets = new CompassTargets();

    private volatile ManhuntSettings settings;
    private volatile ScheduledTask sweep;
    private volatile int sweepPeriod;

    public TrackerCompassService(Plugin plugin, java.util.function.Supplier<Optional<Hunt>> liveHunt,
                                 TrackerCompass compass, PortalMemory portals, Messages messages,
                                 ActionBars actionBars, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.compass = Objects.requireNonNull(compass, "compass");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.messages = messages;
        this.actionBars = actionBars;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.marker = new NamespacedKey(plugin, "manhunt-tracker");
    }

    /** Told the live settings whenever they change — re-arms the sweep if its beat or its very
     *  existence just changed. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
        if (sweep == null) {
            return;
        }
        if (fresh.trackerRefreshTicksClamped() != sweepPeriod) {
            stopSweep();
            startSweep();
        }
    }

    // ------------------------------------------------------------------------ a hunt beginning and ending

    /**
     * A hunt has started: everybody's doors are forgotten, every online Hunter is handed a compass,
     * and the sweep begins. Called by {@code ManhuntMode} as the run is built.
     */
    public void armFor(Hunt hunt) {
        picks.clear();
        portals.clear();
        for (UUID id : hunt.hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter != null) {
                give(hunter);
            }
        }
        startSweep();
    }

    /**
     * The hunt is over: the sweep stops and every compass this module handed out is taken back.
     *
     * <p>The roster is the one handed in rather than the live hunt, because this also runs on the
     * path where the hunt has already been forgotten — see {@code SpeedrunRun.onDisarm}.
     */
    public void disarm(Hunt hunt) {
        stopSweep();
        for (UUID id : hunt.hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter != null) {
                Scheduling.entity(plugin, hunter, () -> {
                    takeBack(hunter);
                    // Every ordinary compass this Hunter carries points at the compass target too, so it
                    // is handed back to the world's spawn, where vanilla keeps it.
                    List<World> worlds = plugin.getServer().getWorlds();
                    if (!worlds.isEmpty()) {
                        hunter.setCompassTarget(worlds.getFirst().getSpawnLocation());
                    }
                });
            }
            if (actionBars != null && showingDistance.remove(id)) {
                actionBars.clear(id, DISTANCE_OWNER);
            }
        }
        picks.clear();
        portals.clear();
        compassTargets.clear();
    }

    /**
     * A Hunter who died and came back gets a fresh compass — always, because theirs was removed from
     * their drops (see {@code TrackerListener.onDeath}) and a Hunter with no compass is not hunting.
     */
    public void giveOnRespawn(Player hunter) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !hunt.isHunter(hunter.getUniqueId())) {
            return;
        }
        // A tick later: on respawn the inventory is still being restored around us, and an item added
        // inside the event itself can be dropped again by that restore.
        Scheduling.entityLater(plugin, hunter, 1L, () -> give(hunter));
    }

    private void startSweep() {
        int period = settings.trackerRefreshTicksClamped();
        sweepPeriod = period;
        sweep = Scheduling.globalTimer(plugin, period, period, handle -> tick());
    }

    private void stopSweep() {
        ScheduledTask running = sweep;
        if (running != null) {
            running.cancel();
        }
        sweep = null;
    }

    // ------------------------------------------------------------------------ the sweep

    private void tick() {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null) {
            return;
        }
        List<Candidate> runners = livingRunners(hunt);
        Map<UUID, String> names = namesOf(runners);
        for (UUID id : hunt.hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }
            Aim aim = compass.aim(pointOf(hunter), runners, picks.get(id));
            Scheduling.entity(plugin, hunter, () -> applyTo(hunter, aim, names));
        }
    }

    /** Every Runner still worth pointing at, in a stable order so cycling is repeatable. */
    private List<Candidate> livingRunners(Hunt hunt) {
        List<Candidate> alive = new ArrayList<>();
        for (UUID id : hunt.livingRunners()) {
            Player runner = plugin.getServer().getPlayer(id);
            if (runner != null && runner.isOnline() && !runner.isDead()) {
                alive.add(new Candidate(id, pointOf(runner)));
            }
        }
        alive.sort(java.util.Comparator.comparing(candidate -> candidate.id().toString()));
        return List.copyOf(alive);
    }

    private Map<UUID, String> namesOf(List<Candidate> runners) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Candidate candidate : runners) {
            Player runner = plugin.getServer().getPlayer(candidate.id());
            names.put(candidate.id(), runner != null ? runner.getName() : "a Runner");
        }
        return names;
    }

    private static Point pointOf(Player player) {
        Location where = player.getLocation();
        String world = where.getWorld() == null ? "" : where.getWorld().getName();
        return new Point(world, where.getX(), where.getY(), where.getZ());
    }

    // ------------------------------------------------------------------------ the item

    /** Gives {@code hunter} a compass, unless they are already carrying one of ours. */
    public void give(Player hunter) {
        if (findTracker(hunter).isPresent()) {
            return;
        }
        place(hunter, freshCompass());
    }

    /**
     * Puts {@code compass} into {@code hunter}'s inventory, or at their feet when it does not fit.
     *
     * <p>{@code Inventory.addItem} does not throw when there is no room — it hands back whatever it
     * could not place. That return value used to be thrown away here, so a Hunter whose inventory
     * happened to be full was told "you have been handed a tracking compass" while nothing landed
     * anywhere: the compass was never dropped, never kept, and the message claimed otherwise. Split
     * out from {@link #give} so the decision is testable without the real Material registry
     * {@link #freshCompass} needs — see {@code TrackerCompassServiceTest}'s own note on why.
     */
    void place(Player hunter, ItemStack compass) {
        java.util.Map<Integer, ItemStack> notFitted = hunter.getInventory().addItem(compass);
        for (ItemStack leftover : notFitted.values()) {
            hunter.getWorld().dropItem(hunter.getLocation(), leftover);
        }
        if (messages != null) {
            messages.send(hunter, "manhunt.tracker.given");
        }
    }

    private void takeBack(Player hunter) {
        ItemStack[] contents = hunter.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTracker(contents[slot])) {
                hunter.getInventory().setItem(slot, null);
            }
        }
    }

    private ItemStack freshCompass() {
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(line("<gold>Tracking compass"));
        meta.lore(List.of(line("<gray>Looking for a Runner…")));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, TAG);
        stack.setItemMeta(meta);
        return stack;
    }

    private void applyTo(Player hunter, Aim aim, Map<UUID, String> names) {
        Optional<Integer> slot = findTracker(hunter);
        if (slot.isEmpty()) {
            return;
        }
        ItemStack stack = hunter.getInventory().getItem(slot.get());
        if (stack == null || !(stack.getItemMeta() instanceof CompassMeta meta)) {
            return;
        }
        String targetName = aim.target() == null ? null : names.getOrDefault(aim.target(), "a Runner");
        showDistance(hunter, aim, targetName);

        switch (aim.kind()) {
            case TRACKING, PORTAL -> {
                meta.displayName(line("<gold>Tracking <white>" + safe(targetName)));
                meta.lore(loreFor(aim.kind() == Aim.Kind.TRACKING
                        ? "<gray>Straight ahead."
                        : "<gray>Through the portal, into <white>" + safe(aim.worldName()) + "<gray>."));
                World here = hunter.getWorld();
                if (needleFromCompassTarget(here.getEnvironment())) {
                    // The needle, without the item: see needleFromCompassTarget.
                    Location target = blockOf(here, aim.at());
                    if (compassTargets.moved(hunter.getUniqueId(), here.getName(),
                            target.getBlockX(), target.getBlockY(), target.getBlockZ())) {
                        hunter.setCompassTarget(target);
                    }
                    if (meta.hasLodestone()) {
                        // Back from the Nether or the End with a lodestone still on it. A lodestone
                        // compass ignores the compass target entirely, so it is swapped once for a
                        // plain one — the one item change a dimension crossing costs.
                        replaceWithPlain(hunter, slot.get(), meta);
                        return;
                    }
                } else {
                    aimAt(meta, here, aim.at());
                }
            }
            case OTHER_WORLD -> {
                meta.setLodestone(null);
                meta.displayName(line("<gold>Tracking <white>" + safe(targetName)));
                meta.lore(List.of(line("<gray>Somewhere in <white>" + safe(aim.worldName()) + "<gray>."),
                        line("<dark_gray>No way through from here.")));
            }
            case NONE -> {
                meta.setLodestone(null);
                meta.displayName(line("<gold>Tracking compass"));
                meta.lore(List.of(line("<gray>Nothing to point at.")));
            }
        }
        // Nothing is written unless something actually changed — see unchanged(). With the needle
        // coming from the compass target in the overworld and the distance on the action bar, what is
        // left on the item — the name and the lore — only changes when the Runner being followed does.
        if (unchanged(stack, meta)) {
            return;
        }
        stack.setItemMeta(meta);
        hunter.getInventory().setItem(slot.get(), stack);
    }

    /**
     * Whether the needle is driven by {@link Player#setCompassTarget} rather than by a lodestone.
     *
     * <h2>Why the compass target, where it can be</h2>
     * Asked for after "it feels like I get a new one every few seconds". A lodestone is a fixed spot
     * stored <em>in the item</em>: following a Runner who moves means changing the item, and a client
     * redraws an item that changed — in a hand, that is the equip animation. The compass target is
     * per player and sent to that one client; a plain compass points at it, and moving it touches the
     * item not at all. The needle can follow every step and the compass never so much as twitches.
     *
     * <h2>Why only in the overworld</h2>
     * A plain compass spins in the Nether and the End — that is vanilla, not this module — so there the
     * lodestone stays, written only when the Runner actually leaves a block. Whether the compass target
     * would hold there too has not been checked against a client; if it does, this can widen.
     */
    static boolean needleFromCompassTarget(World.Environment environment) {
        return environment == World.Environment.NORMAL;
    }

    private static Location blockOf(World world, Point at) {
        return new Location(world, Math.floor(at.x()), Math.floor(at.y()), Math.floor(at.z()));
    }

    /** A plain compass carrying the same name and lore, in place of a lodestone one. */
    private void replaceWithPlain(Player hunter, int slot, CompassMeta carried) {
        ItemStack plain = freshCompass();
        ItemMeta meta = plain.getItemMeta();
        meta.displayName(carried.displayName());
        meta.lore(carried.lore());
        plain.setItemMeta(meta);
        hunter.getInventory().setItem(slot, plain);
    }

    /**
     * The distance, on the action bar, while the Hunter is holding the compass.
     *
     * <p>It used to be a lore line, and a lore line that changes is an item that changes — the same
     * redraw the compass target exists to avoid. The action bar redraws text, not an item, so it can
     * say the distance to the block as often as it likes. Only while the compass is in a hand: the
     * hunt's clock owns the bar the rest of the time, and this sits above it only while there is a
     * reason to.
     */
    private void showDistance(Player hunter, Aim aim, String targetName) {
        if (actionBars == null || messages == null) {
            return;
        }
        UUID id = hunter.getUniqueId();
        boolean pointing = aim.kind() == Aim.Kind.TRACKING || aim.kind() == Aim.Kind.PORTAL;
        if (!compass.showsDistance() || !pointing || !holdingTracker(hunter)) {
            if (showingDistance.remove(id)) {
                actionBars.clear(id, DISTANCE_OWNER);
            }
            return;
        }
        actionBars.show(id, DISTANCE_OWNER,
                messages.get("manhunt.tracker.distance",
                        "runner", safe(targetName),
                        "blocks", String.valueOf(Math.round(aim.distance()))),
                Duration.ofMillis(Math.max(1, sweepPeriod) * 50L + 1000L), ActionBarPriority.NORMAL);
        showingDistance.add(id);
    }

    private boolean holdingTracker(Player hunter) {
        return isTracker(hunter.getInventory().getItemInMainHand())
                || isTracker(hunter.getInventory().getItemInOffHand());
    }

    /**
     * Whether the meta about to be written says exactly what the item already says.
     *
     * <p>{@link ItemMeta} is a copy taken from the stack, so the comparison is against the stack's own
     * current meta rather than against the object being edited. Adventure's components and Bukkit's
     * {@link Location} both have real equality, so this is a genuine "would this write change
     * anything" and not an approximation of one.
     */
    private static boolean unchanged(ItemStack stack, CompassMeta edited) {
        return stack.getItemMeta() instanceof CompassMeta current
                && Objects.equals(current.displayName(), edited.displayName())
                && Objects.equals(current.lore(), edited.lore())
                && Objects.equals(current.getLodestone(), edited.getLodestone())
                && current.isLodestoneTracked() == edited.isLodestoneTracked();
    }

    /**
     * Points the needle at a spot, rounded to the block it is in.
     *
     * <p>The rounding is what stops the item being rewritten on every single sweep. A needle is a
     * direction, and no direction anybody can see changes within one block — but a {@link Location}
     * built from raw doubles differs from the last one every time a Runner so much as walks, which
     * made every sweep a real change and every real change a redraw of the item in somebody's hand.
     * Whole blocks give exactly the same needle and change only when the Runner actually leaves a
     * block.
     */
    private static void aimAt(CompassMeta meta, World world, Point at) {
        meta.setLodestoneTracked(false);
        meta.setLodestone(new Location(world,
                Math.floor(at.x()), Math.floor(at.y()), Math.floor(at.z())));
    }

    /** The item's lore: what the needle means, and how to change it. No distance — see showDistance. */
    List<net.kyori.adventure.text.Component> loreFor(String first) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(line(first));
        if (compass.allowsPicking()) {
            lore.add(line("<dark_gray>Right-click for the next Runner, or the nearest."));
        }
        return lore;
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /** A player-supplied name never reaches MiniMessage as markup — see {@code Chat}'s own rule. */
    private static String safe(String raw) {
        return raw == null ? "somebody" : raw.replace("<", "").replace(">", "");
    }

    private Optional<Integer> findTracker(Player hunter) {
        ItemStack[] contents = hunter.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTracker(contents[slot])) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code stack} is one of the compasses this module handed out. */
    public boolean isTracker(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS || !stack.hasItemMeta()) {
            return false;
        }
        return TAG.equals(stack.getItemMeta().getPersistentDataContainer()
                .get(marker, PersistentDataType.STRING));
    }

    // ------------------------------------------------------------------------ picking a Runner

    /**
     * A Hunter right-clicked their compass: move it one position along the cycle — the next Runner
     * in the roster, and after the last of them back to "whoever is nearest". Refused outright when
     * {@code tracker-hunter-may-choose} is off, where the needle is the owner's to set and not the
     * Hunter's.
     */
    public void cycleTarget(Player hunter) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !hunt.isHunter(hunter.getUniqueId())) {
            return;
        }
        if (!compass.allowsPicking()) {
            say(hunter, "manhunt.tracker.picking-off");
            return;
        }
        List<Candidate> runners = livingRunners(hunt);
        Optional<Following> next = TrackerCompass.next(runners, current(hunter.getUniqueId()));
        if (next.isEmpty()) {
            say(hunter, "manhunt.tracker.no-runners");
            return;
        }
        Following moved = next.get();
        String name = nameOf(moved.runner());
        picks.put(hunter.getUniqueId(), moved);
        if (moved.isNearest()) {
            say(hunter, "manhunt.tracker.now-nearest");
        } else {
            say(hunter, "manhunt.tracker.now-following", "runner", name);
        }
        // Redrawn at once rather than at the next sweep: a compass that answers a click a second
        // later is a compass the Hunter clicks again.
        Aim aim = compass.aim(pointOf(hunter), runners, moved);
        applyTo(hunter, aim, namesOf(runners));
    }

    /**
     * Where {@code hunter}'s compass is right now: their own pick, or nothing at all for a Hunter who
     * has never touched it — which {@link TrackerCompass#next} reads as "on the nearest", the
     * position the first right-click of a hunt moves one step from.
     */
    private Following current(UUID hunter) {
        return picks.get(hunter);
    }

    private String nameOf(UUID runner) {
        if (runner == null) {
            return "a Runner";
        }
        Player player = plugin.getServer().getPlayer(runner);
        return player != null ? player.getName() : "a Runner";
    }

    /** Forgets a Hunter's pick — they left the side, or the server. */
    public void forget(UUID hunter) {
        picks.remove(hunter);
        showingDistance.remove(hunter);
        compassTargets.forget(hunter);
    }

    /** What {@code hunter} has set their own compass to, if they have set it at all. */
    public Optional<Following> pickOf(UUID hunter) {
        return Optional.ofNullable(picks.get(hunter));
    }

    private void say(Player player, String key, String... placeholders) {
        if (messages != null) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }

    public String describe() {
        return "handing the Hunters a compass and keeping its needle on a Runner";
    }
}
