package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.visual.PathTrail;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Aim;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Candidate;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Following;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Point;
import de.raindancer.modules.speedrun.manhunt.util.Threads;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static de.raindancer.modules.speedrun.manhunt.tracker.CompassItems.line;
import static de.raindancer.modules.speedrun.manhunt.tracker.CompassItems.safe;

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
 * {@link #needleFor}. The distance is on the action bar, not in the lore, for the same
 * reason.
 *
 * <h2>Why the timer restarts on a settings change</h2>
 * A Paper repeating task's period is fixed when it is scheduled. Rather than run every tick and skip
 * most of them — paying for a hundred wake-ups to use ten — the timer is cancelled and re-armed when
 * the configured interval actually changes, which is a thing that happens once in a menu click, not
 * once a tick.
 *
 * <h2>Thread notes</h2>
 * The sweep reads every Runner's position from the global region thread; each Hunter's inventory is
 * then written on that Hunter's own entity scheduler, so the item mutation is always on the thread
 * owning them even under Folia.
 */
public final class TrackerCompassService {

    private static final String TAG = "tracker";
    private static final Predicate<String> OURS = TAG::equals;

    private final Plugin plugin;
    /** The hunt in progress, or empty between hunts — held by the mode, never copied here. */
    private final Supplier<Optional<Hunt>> liveHunt;
    private final TrackerCompass compass;
    private final PortalMemory portals;
    private final Messages messages;
    private final ActionBars actionBars;
    private final CompassItems items;

    /** The action bar slot the distance is shown in — its own, so it never takes turns with the clock. */
    static final String DISTANCE_OWNER = "manhunt-tracker";

    /** Hunters currently being shown a distance, so the slot is cleared once and not every sweep. */
    private final Set<UUID> showingDistance = ConcurrentHashMap.newKeySet();

    /**
     * What each Hunter has set their own compass to. Never a {@code Player} — see
     * {@link PortalMemory}. An absent entry is a Hunter who has never right-clicked it; both that and
     * {@link Following#NEAREST} follow whoever is nearest.
     */
    private final Map<UUID, Following> picks = new ConcurrentHashMap<>();

    /** The compass target each Hunter was last sent — see {@link CompassTargets}. */
    private final CompassTargets compassTargets = new CompassTargets();

    private volatile ManhuntSettings settings;
    /** Opens the list a Hunter picks from — set by the module, which has the brand a menu needs. */
    private volatile Consumer<Player> pickerScreen;
    private volatile ScheduledTask sweep;
    private volatile int sweepPeriod;

    public TrackerCompassService(Plugin plugin, Supplier<Optional<Hunt>> liveHunt,
                                 TrackerCompass compass, PortalMemory portals, Messages messages,
                                 ActionBars actionBars, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.compass = Objects.requireNonNull(compass, "compass");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.messages = messages;
        this.actionBars = actionBars;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.items = new CompassItems(plugin, "manhunt-tracker", Material.COMPASS);
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
     * A hunt has started: every pick and door is forgotten and the sweep begins. Handing the compasses
     * out is {@link HuntCompasses}', on each player's own thread.
     */
    public void arm() {
        picks.clear();
        portals.clear();
        compassTargets.clear();
        stopSweep();
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
        for (UUID id : hunt.everybody()) {
            Player holder = plugin.getServer().getPlayer(id);
            if (holder != null) {
                Threads.entity(plugin, holder, () -> {
                    takeBack(holder);
                    // Every ordinary compass this player carries points at the compass target too, so
                    // it is handed back to the world's spawn, where vanilla keeps it.
                    List<World> worlds = plugin.getServer().getWorlds();
                    if (!worlds.isEmpty()) {
                        holder.setCompassTarget(worlds.getFirst().getSpawnLocation());
                    }
                });
            }
            clearDistance(id);
        }
        picks.clear();
        portals.clear();
        compassTargets.clear();
    }

    /**
     * Brings one player's compass in line with the side they are on and the settings as they are —
     * on their own thread. A holder is handed one if they lack it; anybody else has theirs taken,
     * and whatever it was showing them goes with it.
     */
    public void fit(Hunt hunt, Player player) {
        if (isHolder(hunt, player.getUniqueId())) {
            give(player);
        } else {
            takeBack(player);
            forget(player.getUniqueId());
        }
    }

    /**
     * A Hunter who died and came back gets a fresh compass — always, because theirs was removed from
     * their drops (see {@code TrackerListener.onDeath}) and a Hunter with no compass is not hunting.
     */
    public void giveOnRespawn(Player hunter) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !isHolder(hunt, hunter.getUniqueId())) {
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
        List<Candidate> hunters = settings.runnerCompass() ? livingHunters(hunt) : List.of();
        Map<UUID, String> names = namesOf(runners, hunters);
        for (UUID id : holders(hunt)) {
            Player holder = plugin.getServer().getPlayer(id);
            if (holder == null) {
                continue;
            }
            Aim aim = compass.aim(pointOf(holder), hunt.isRunner(id) ? hunters : runners, picks.get(id));
            Scheduling.entity(plugin, holder, () -> applyTo(holder, aim, names));
        }
        Consumer<Hunt> then = afterSweep;
        if (then != null) {
            then.accept(hunt);
        }
    }

    /** Run after every sweep, on the same beat — the other two compasses ride on this timer. */
    private volatile Consumer<Hunt> afterSweep;

    /** The team compass, for the listener that answers its clicks. Null where nothing wired it. */
    private volatile TeamCompassService team;

    public void afterSweep(Consumer<Hunt> then) {
        this.afterSweep = then;
    }

    public void teamCompass(TeamCompassService companion) {
        this.team = companion;
    }

    public Optional<TeamCompassService> team() {
        return Optional.ofNullable(team);
    }

    /**
     * Who carries a tracking compass: every Hunter, and — with {@link ManhuntSettings#runnerCompass()}
     * — every Runner still in it.
     */
    public boolean isHolder(Hunt hunt, UUID player) {
        return hunt.isHunter(player)
                || (settings.runnerCompass() && hunt.isRunner(player) && !hunt.isEliminated(player));
    }

    private Set<UUID> holders(Hunt hunt) {
        Set<UUID> holders = new LinkedHashSet<>(hunt.hunters());
        if (settings.runnerCompass()) {
            holders.addAll(hunt.livingRunners());
        }
        return holders;
    }

    /** What {@code holder} tracks: the Hunters for a Runner, the Runners for everybody else. */
    private List<Candidate> targetsOf(Hunt hunt, UUID holder) {
        return hunt.isRunner(holder) ? livingHunters(hunt) : livingRunners(hunt);
    }

    /** Every Hunter online and alive, stable order — what a Runner's compass points at. */
    private List<Candidate> livingHunters(Hunt hunt) {
        return alive(hunt.hunters());
    }

    /** Every Runner still worth pointing at, in a stable order so cycling is repeatable. */
    private List<Candidate> livingRunners(Hunt hunt) {
        return alive(hunt.livingRunners());
    }

    private List<Candidate> alive(Set<UUID> ids) {
        List<Candidate> alive = new ArrayList<>();
        for (UUID id : ids) {
            Player player = plugin.getServer().getPlayer(id);
            // A spectator is somebody watching, not somebody to find — staff, or a Runner made to watch.
            if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR) {
                alive.add(new Candidate(id, pointOf(player)));
            }
        }
        alive.sort(Comparator.comparing(candidate -> candidate.id().toString()));
        return List.copyOf(alive);
    }

    @SafeVarargs
    private Map<UUID, String> namesOf(List<Candidate>... sides) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (List<Candidate> side : sides) {
            for (Candidate candidate : side) {
                names.put(candidate.id(), nameOf(candidate.id()));
            }
        }
        return Map.copyOf(names);
    }

    static Point pointOf(Player player) {
        Location where = player.getLocation();
        String world = where.getWorld() == null ? "" : where.getWorld().getName();
        return new Point(world, where.getX(), where.getY(), where.getZ());
    }

    // ------------------------------------------------------------------------ the item

    public boolean carries(Player hunter) {
        return items.slotOf(hunter, OURS) >= 0;
    }

    /** Gives {@code hunter} a compass, unless they are already carrying one of ours. */
    public void give(Player hunter) {
        if (carries(hunter)) {
            return;
        }
        place(hunter, freshCompass());
    }

    /**
     * Puts {@code compass} into {@code hunter}'s inventory, or at their feet when it does not fit, and
     * says so. Split out from {@link #give} so it is testable without the real Material registry
     * {@link #freshCompass} needs — see {@code TrackerCompassServiceTest}'s own note on why.
     */
    void place(Player hunter, ItemStack compass) {
        CompassItems.handTo(hunter, compass);
        say(hunter, "manhunt.tracker.given");
    }

    /**
     * Takes this module's compass off {@code hunter} and forgets whatever they were following —
     * for somebody who has just stopped being a Hunter mid-hunt, where {@link #disarm} (which does
     * the same for everybody, at the end) is far too big a hammer.
     */
    public void takeFrom(Player hunter) {
        Threads.entity(plugin, hunter, () -> takeBack(hunter));
        forget(hunter.getUniqueId());
    }

    /** Every tracking compass off {@code holder} — on their own thread. */
    public void takeBack(Player holder) {
        items.removeAll(holder, OURS);
    }

    private ItemStack freshCompass() {
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(line("<gold>Tracking compass"));
        meta.lore(List.of(line("<gray>Looking for somebody to follow…")));
        items.tag(meta, TAG);
        stack.setItemMeta(meta);
        // A dropped compass is a working compass in a Runner's hands; Core refuses the drop.
        return BoundItems.bind(stack);
    }

    private void applyTo(Player hunter, Aim aim, Map<UUID, String> names) {
        int slot = items.slotOf(hunter, OURS);
        if (slot < 0) {
            return;
        }
        ItemStack stack = hunter.getInventory().getItem(slot);
        if (stack == null || !(stack.getItemMeta() instanceof CompassMeta meta)) {
            return;
        }
        String targetName = aim.target() == null ? null : names.getOrDefault(aim.target(), "a Runner");
        boolean holding = holdingTracker(hunter);
        showDistance(hunter, aim, targetName, holding);
        if (holding) {
            showTrail(hunter, aim);
        }
        String heading = "<gold>Tracking <white>" + safe(targetName);

        switch (aim.kind()) {
            case TRACKING, PORTAL -> {
                meta.displayName(line(heading));
                meta.lore(loreFor(aim.kind() == Aim.Kind.TRACKING
                        ? "<gray>Straight ahead."
                        : "<gray>Through the portal, into <white>" + safe(aim.worldName()) + "<gray>."));
                World here = hunter.getWorld();
                if (needleFor(aim.kind(), here.getEnvironment()) == Needle.COMPASS_TARGET) {
                    // The needle, without the item: see needleFor.
                    Location target = blockOf(here, aim.at());
                    if (compassTargets.moved(hunter.getUniqueId(), here.getName(),
                            target.getBlockX(), target.getBlockY(), target.getBlockZ())) {
                        hunter.setCompassTarget(target);
                    }
                    if (meta.hasLodestone()) {
                        // Back from the Nether or the End — or back from following a door — with a
                        // lodestone still on it. A lodestone compass ignores the compass target
                        // entirely, so it is swapped once for a plain one.
                        replaceWithPlain(hunter, slot, meta);
                        return;
                    }
                } else {
                    aimAt(meta, here, aim.at());
                    // While the item holds the needle, nothing is being sent to the client: what it
                    // was last sent is no longer what it is showing, so the next sweep that goes back
                    // to the compass target has to send it again rather than recognise it.
                    compassTargets.forget(hunter.getUniqueId());
                }
            }
            case OTHER_WORLD -> {
                meta.setLodestone(null);
                meta.displayName(line(heading));
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
        hunter.getInventory().setItem(slot, stack);
    }

    /** Where a Hunter's needle comes from: the client's compass target, or a lodestone in the item. */
    enum Needle { COMPASS_TARGET, LODESTONE }

    /**
     * Which of the two drives the needle for this aim, in this dimension.
     *
     * <h2>Why the compass target, where it can be</h2>
     * Asked for after "it feels like I get a new one every few seconds". A lodestone is a fixed spot
     * stored <em>in the item</em>: following a Runner who moves means changing the item, and a client
     * redraws an item that changed — in a hand, that is the equip animation. The compass target is
     * per player and sent to that one client; a plain compass points at it, and moving it touches the
     * item not at all. The needle can follow every step and the compass never so much as twitches.
     *
     * <h2>Why a door is a lodestone even in the overworld</h2>
     * Reported live: the Runner went into the Nether, the action bar showed the distance to the door —
     * so the door was known and the aim was right — and the needle spun anyway. A compass target is
     * the client's <em>spawn position</em>, which the server sends on its own account too (a respawn,
     * a dimension change, a world's spawn being moved); anything it sends overwrites what this sent,
     * and {@link CompassTargets} then recognises the target as already sent and never repeats it. A
     * moving Runner papers over that within a block or two. A door does not move, so the same
     * overwrite leaves the needle pointing at a spawn nobody is near — a needle that swings uselessly
     * where the Hunter is standing, which is what "it spins" is. The item holds a door for nothing:
     * it is written once when the Runner goes down and not again until they come back up.
     *
     * <h2>Why the Nether and the End are always the item</h2>
     * A plain compass spins there — that is vanilla, not this module — so the lodestone is the only
     * needle available at all.
     */
    static Needle needleFor(Aim.Kind kind, World.Environment environment) {
        if (kind == Aim.Kind.PORTAL || environment != World.Environment.NORMAL) {
            return Needle.LODESTONE;
        }
        return Needle.COMPASS_TARGET;
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
    private void showDistance(Player hunter, Aim aim, String targetName, boolean holding) {
        if (actionBars == null || messages == null) {
            return;
        }
        UUID id = hunter.getUniqueId();
        boolean pointing = aim.kind() == Aim.Kind.TRACKING || aim.kind() == Aim.Kind.PORTAL;
        if (!compass.showsDistance() || !pointing || !holding) {
            clearDistance(id);
            return;
        }
        actionBars.show(id, DISTANCE_OWNER,
                messages.get("manhunt.tracker.distance",
                        "runner", safe(targetName),
                        "blocks", String.valueOf(Math.round(aim.distance()))),
                Duration.ofMillis(Math.max(1, sweepPeriod) * 50L + 1000L), ActionBarPriority.NORMAL);
        showingDistance.add(id);
    }

    private void clearDistance(UUID id) {
        if (showingDistance.remove(id) && actionBars != null) {
            actionBars.clear(id, DISTANCE_OWNER);
        }
    }

    /**
     * The particle trail, drawn to this Hunter alone while they hold the compass — every sweep, so it
     * follows a moving Runner. Lime for a Runner, purple for a door.
     */
    private void showTrail(Player hunter, Aim aim) {
        if (!TrailPreference.shows(hunter, settings)) {
            return;
        }
        List<PathTrail.Dot> dots = TrackerCompass.trail(pointOf(hunter), aim);
        if (dots.isEmpty()) {
            return;
        }
        Color colour = aim.kind() == Aim.Kind.PORTAL ? Color.PURPLE : Color.LIME;
        PathTrail.draw(hunter, hunter.getWorld(), dots, new Particle.DustOptions(colour, 1.0f));
    }

    boolean holdingTracker(Player hunter) {
        return items.inHand(hunter, OURS);
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
    List<Component> loreFor(String first) {
        List<Component> lore = new ArrayList<>();
        lore.add(line(first));
        if (compass.allowsPicking()) {
            lore.add(line("<dark_gray>Right-click for the next one, or the nearest."));
            lore.add(line("<dark_gray>Sneak + right-click to pick from a list."));
        }
        return lore;
    }

    /** Whether {@code stack} is one of the compasses this module handed out. */
    public boolean isTracker(ItemStack stack) {
        return items.is(stack, OURS);
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
        if (hunt == null || !isHolder(hunt, hunter.getUniqueId())) {
            return;
        }
        if (!compass.allowsPicking()) {
            say(hunter, "manhunt.tracker.picking-off");
            return;
        }
        List<Candidate> runners = targetsOf(hunt, hunter.getUniqueId());
        Optional<Following> next = TrackerCompass.next(runners, current(hunter.getUniqueId()));
        if (next.isEmpty()) {
            say(hunter, "manhunt.tracker.no-runners");
            return;
        }
        follow(hunter, next.get(), runners);
    }

    /**
     * A Hunter picked {@code choice} from the list — see {@code ManhuntTrackerMenu}. Refused, like a
     * right-click, where the owner aims the compass; and a pick that is no longer in the hunt (a
     * Runner caught while the list was open) is simply ignored.
     */
    public void pick(Player hunter, Following choice) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !isHolder(hunt, hunter.getUniqueId()) || choice == null) {
            return;
        }
        if (!compass.allowsPicking()) {
            say(hunter, "manhunt.tracker.picking-off");
            return;
        }
        List<Candidate> runners = targetsOf(hunt, hunter.getUniqueId());
        if (!choice.isNearest() && runners.stream().noneMatch(c -> c.id().equals(choice.runner()))) {
            return;
        }
        follow(hunter, choice, runners);
    }

    private void follow(Player hunter, Following moved, List<Candidate> runners) {
        picks.put(hunter.getUniqueId(), moved);
        if (moved.isNearest()) {
            say(hunter, "manhunt.tracker.now-nearest");
        } else {
            say(hunter, "manhunt.tracker.now-following", "runner", nameOf(moved.runner()));
        }
        // Redrawn at once rather than at the next sweep: a compass that answers a click a second
        // later is a compass the Hunter clicks again.
        Aim aim = compass.aim(pointOf(hunter), runners, moved);
        applyTo(hunter, aim, namesOf(runners));
    }

    /** One line of the list a Hunter picks from. */
    public record Target(Following following, String name, boolean teammate, boolean current) {
    }

    /** What {@code hunter} may pick: the nearest Runner, and every Runner. Empty outside a hunt. */
    public List<Target> targetsFor(Player hunter) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !isHolder(hunt, hunter.getUniqueId())) {
            return List.of();
        }
        Following now = picks.get(hunter.getUniqueId());
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(Following.NEAREST, "Whoever is nearest", false,
                now == null || now.isNearest()));
        for (Candidate runner : targetsOf(hunt, hunter.getUniqueId())) {
            targets.add(new Target(Following.of(runner.id()), nameOf(runner.id()), false,
                    now != null && runner.id().equals(now.runner())));
        }
        return targets;
    }

    /** Tells the service how to open the pick list — the module owns the brand a menu needs. */
    public void pickerScreen(Consumer<Player> opener) {
        this.pickerScreen = opener;
    }

    /** A Hunter sneak-right-clicked their compass: the list, where picking is allowed at all. */
    public void openPicker(Player hunter) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !isHolder(hunt, hunter.getUniqueId())) {
            return;
        }
        if (!compass.allowsPicking()) {
            say(hunter, "manhunt.tracker.picking-off");
            return;
        }
        Consumer<Player> opener = pickerScreen;
        if (opener == null) {
            cycleTarget(hunter);   // no screen wired (tests, or a host without menus): cycling still works
            return;
        }
        opener.accept(hunter);
    }

    /**
     * Where {@code hunter}'s compass is right now: their own pick, or nothing at all for a Hunter who
     * has never touched it — which {@link TrackerCompass#next} reads as "on the nearest", the
     * position the first right-click of a hunt moves one step from.
     */
    private Following current(UUID hunter) {
        return picks.get(hunter);
    }

    private String nameOf(UUID player) {
        if (player == null) {
            return "somebody";
        }
        Player online = plugin.getServer().getPlayer(player);
        return online != null ? online.getName() : "somebody";
    }

    /**
     * Sends this Hunter's needle again on the next sweep, whether or not the target has moved.
     *
     * <p>For the moments the server sends that client a spawn position of its own — a respawn, a
     * dimension change — which silently replaces the compass target the sweep last sent while
     * {@link CompassTargets} still believes it is the one showing. Without this, a Hunter who died
     * once carried a needle pointing at a spawn until the Runner happened to leave the block the
     * sweep last sent.
     */
    public void resyncNeedle(UUID hunter) {
        compassTargets.forget(hunter);
    }

    /** Forgets a Hunter's pick and clears what they were being shown — they left the side, or the server. */
    public void forget(UUID hunter) {
        picks.remove(hunter);
        compassTargets.forget(hunter);
        clearDistance(hunter);
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
