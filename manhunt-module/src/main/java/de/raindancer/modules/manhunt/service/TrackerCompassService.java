package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.service.TrackerCompass.Candidate;
import de.raindancer.modules.manhunt.service.TrackerCompass.Following;
import de.raindancer.modules.manhunt.service.TrackerCompass.Point;
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
 * "which Runner, and where does the needle go" testable without a server, and it is the same shape
 * {@link ManhuntLobbyBox}/{@link ManhuntLobbyListener} already have.
 *
 * <h2>A lodestone that is not a lodestone</h2>
 * {@link CompassMeta#setLodestone} with {@link CompassMeta#setLodestoneTracked(boolean) tracked =
 * false} is the only way to aim a compass at an arbitrary spot: tracked compasses insist there is a
 * real lodestone block at the target and go blank when there is not, which every moving Runner
 * guarantees. The needle is therefore re-pointed at whatever spot the aim names, every
 * {@link ManhuntSettings#trackerRefreshTicks()} ticks, rather than following anything by itself.
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
    private final ManhuntService manhunt;
    private final TrackerCompass compass;
    private final PortalMemory portals;
    private final Messages messages;
    private final NamespacedKey marker;

    /**
     * What each Hunter has set their own compass to. Never a {@code Player} — see
     * {@link PortalMemory}. An absent entry is a Hunter who has never right-clicked it, which is not
     * the same as one who cycled back to {@link Following#NEAREST}: the first takes whatever
     * {@code tracker-targets} says, the second has said it themselves and outranks it.
     */
    private final Map<UUID, Following> picks = new ConcurrentHashMap<>();

    private volatile ManhuntSettings settings;
    private volatile ScheduledTask sweep;
    private volatile int sweepPeriod;

    public TrackerCompassService(Plugin plugin, ManhuntService manhunt, TrackerCompass compass,
                                 PortalMemory portals, Messages messages, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.compass = Objects.requireNonNull(compass, "compass");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.messages = messages;
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
        if (!fresh.trackerCompassEnabled()) {
            stopSweep();
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
     * and the sweep begins. Called from {@code ManhuntModule}'s own composition of
     * {@code ManhuntService.onStart}, not from inside the service — see that hook's javadoc.
     */
    public void armFor(java.util.Set<UUID> roster) {
        picks.clear();
        portals.clear();
        if (!settings.trackerCompassEnabled()) {
            return;
        }
        for (UUID id : manhunt.teams().hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter != null) {
                give(hunter);
            }
        }
        startSweep();
    }

    /** The hunt is over: the sweep stops and every compass this module handed out is taken back. */
    public void disarm() {
        stopSweep();
        for (UUID id : manhunt.teams().hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter != null) {
                Scheduling.entity(plugin, hunter, () -> takeBack(hunter));
            }
        }
        picks.clear();
        portals.clear();
    }

    /** A Hunter who died and came back — handed a replacement, if the owner allows one. */
    public void giveOnRespawn(Player hunter) {
        if (!manhunt.isRunning() || !settings.trackerCompassEnabled()
                || !settings.trackerGiveOnRespawn()
                || !manhunt.teams().isHunter(hunter.getUniqueId())) {
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
        if (!manhunt.isRunning() || !settings.trackerCompassEnabled()) {
            return;
        }
        List<Candidate> runners = livingRunners();
        Map<UUID, String> names = namesOf(runners);
        for (UUID id : manhunt.teams().hunters()) {
            Player hunter = plugin.getServer().getPlayer(id);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }
            Aim aim = compass.aim(pointOf(hunter), runners, picks.get(id));
            Scheduling.entity(plugin, hunter, () -> applyTo(hunter, aim, names));
        }
    }

    /** Every Runner still worth pointing at, in a stable order so cycling is repeatable. */
    private List<Candidate> livingRunners() {
        List<Candidate> alive = new ArrayList<>();
        for (UUID id : manhunt.teams().runners()) {
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
        switch (aim.kind()) {
            case TRACKING -> {
                aimAt(meta, hunter.getWorld(), aim.at());
                meta.displayName(line("<gold>Tracking <white>" + safe(targetName)));
                meta.lore(loreFor("<gray>Straight ahead.", aim));
            }
            case PORTAL -> {
                aimAt(meta, hunter.getWorld(), aim.at());
                meta.displayName(line("<gold>Tracking <white>" + safe(targetName)));
                meta.lore(loreFor("<gray>Through the portal, into <white>"
                        + safe(aim.worldName()) + "<gray>.", aim));
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
        // Nothing is written unless something actually changed — see unchanged().
        //
        // Reported as "it feels like I get a new one every few seconds": this ran on every sweep,
        // twice a second by default, and each run replaced the item in its slot. The client redraws a
        // slot whose item changed, and redrawing the item in a hand is the equip animation — so a
        // compass that was pointing at the same place, with the same name and the same lore, still
        // flickered like a fresh item twice a second. It is the same compass; it should look like it.
        if (unchanged(stack, meta)) {
            return;
        }
        stack.setItemMeta(meta);
        hunter.getInventory().setItem(slot.get(), stack);
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

    /** Points the needle at {@code at} — see the class javadoc on why tracking is switched off. */
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

    private List<net.kyori.adventure.text.Component> loreFor(String first, Aim aim) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(line(first));
        if (compass.showsDistance()) {
            // To the nearest five blocks, and the word is "about" for exactly that reason. A figure
            // to the block changes every time either of them takes a step, and a lore line that
            // changes is an item that changes, which is a redraw of the compass in somebody's hand —
            // see applyTo's own note. Five blocks is below what anybody reads off a chase anyway.
            lore.add(line("<gray>About <white>" + roundedDistance(aim.distance())
                    + "<gray> blocks away."));
        }
        if (compass.allowsPicking()) {
            lore.add(line(aim.target() == null
                    ? "<dark_gray>Right-click to lock onto a Runner."
                    : "<dark_gray>Right-click for the next Runner, or the nearest."));
        }
        return lore;
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * A distance to the nearest five blocks, never below five while there is any distance at all.
     *
     * <p>Kept off zero on purpose: "about 0 blocks away" reads as a bug rather than as "right on top
     * of them", and the one case where a Hunter does not need a number is the one where they can see
     * the Runner.
     */
    static long roundedDistance(double blocks) {
        long rounded = Math.round(blocks / 5.0) * 5;
        return rounded == 0 && blocks > 0 ? 5 : rounded;
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
        if (!manhunt.isRunning()) {
            return;
        }
        if (!compass.allowsPicking()) {
            say(hunter, "manhunt.tracker.picking-off");
            return;
        }
        List<Candidate> runners = livingRunners();
        Optional<Following> next = TrackerCompass.next(runners, current(hunter.getUniqueId(), runners));
        if (next.isEmpty()) {
            say(hunter, "manhunt.tracker.no-runners");
            return;
        }
        Following moved = next.get();
        String name = nameOf(moved.runner());
        if (settings.trackerSharedTarget()) {
            // One pack, one needle: everybody's compass turns, and everybody is told why — a Hunter
            // whose own view swung without explanation would reasonably think it had broken.
            for (UUID id : manhunt.teams().hunters()) {
                picks.put(id, moved);
                Player other = plugin.getServer().getPlayer(id);
                if (other != null && !other.equals(hunter)) {
                    if (moved.isNearest()) {
                        say(other, "manhunt.tracker.pack-nearest", "hunter", hunter.getName());
                    } else {
                        say(other, "manhunt.tracker.pack-following", "runner", name,
                                "hunter", hunter.getName());
                    }
                }
            }
        } else {
            picks.put(hunter.getUniqueId(), moved);
        }
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
     * Where {@code hunter}'s compass is right now — their own setting, or the starting point the
     * owner's {@code tracker-targets} names for a Hunter who has never touched it. Resolved before
     * cycling so the first right-click of a hunt moves one position rather than appearing to jump.
     */
    private Following current(UUID hunter, List<Candidate> runners) {
        Following own = picks.get(hunter);
        return own != null ? own : compass.startingPoint(runners);
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
