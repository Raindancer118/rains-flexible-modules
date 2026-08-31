package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.service.TrackerCompass.Candidate;
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

    /** Which Runner each Hunter has picked. Never a {@code Player} — see {@link PortalMemory}. */
    private final Map<UUID, UUID> picks = new ConcurrentHashMap<>();

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
        hunter.getInventory().addItem(freshCompass());
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
        stack.setItemMeta(meta);
        hunter.getInventory().setItem(slot.get(), stack);
    }

    /** Points the needle at {@code at} — see the class javadoc on why tracking is switched off. */
    private static void aimAt(CompassMeta meta, World world, Point at) {
        meta.setLodestoneTracked(false);
        meta.setLodestone(new Location(world, at.x(), at.y(), at.z()));
    }

    private List<net.kyori.adventure.text.Component> loreFor(String first, Aim aim) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(line(first));
        if (compass.showsDistance()) {
            lore.add(line("<gray>About <white>" + Math.round(aim.distance()) + "<gray> blocks away."));
        }
        if (compass.allowsPicking()) {
            lore.add(line("<dark_gray>Right-click to follow the next Runner."));
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
     * A Hunter right-clicked their compass: follow the next Runner along. Refused outright under
     * {@link ManhuntSettings.TrackerTargets#NEAREST}, where the needle is the owner's choice and not
     * the Hunter's.
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
        Optional<UUID> next = TrackerCompass.next(runners, picks.get(hunter.getUniqueId()));
        if (next.isEmpty()) {
            say(hunter, "manhunt.tracker.no-runners");
            return;
        }
        picks.put(hunter.getUniqueId(), next.get());
        Player runner = plugin.getServer().getPlayer(next.get());
        say(hunter, "manhunt.tracker.now-following", "runner",
                runner != null ? runner.getName() : "a Runner");
        Aim aim = compass.aim(pointOf(hunter), runners, next.get());
        Map<UUID, String> names = namesOf(runners);
        applyTo(hunter, aim, names);
    }

    /** Forgets a Hunter's pick — they left the side, or the server. */
    public void forget(UUID hunter) {
        picks.remove(hunter);
    }

    /** Which Runner {@code hunter} is following right now, if they have picked one. */
    public Optional<UUID> pickOf(UUID hunter) {
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
