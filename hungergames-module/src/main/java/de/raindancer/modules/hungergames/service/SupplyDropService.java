package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Schedule;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Timed Capitol supply drops: at configured game-time points, a warning goes out and — once the warning
 * has run — a loot crate lands at a safe random spot, with an optional beacon, firework and particles.
 *
 * <h2>Why there is no {@code BukkitRunnable} or {@code runTaskTimer} anywhere in here</h2>
 * The source engine ran its own second-tick timer and, per drop, a further {@code runTaskLater} for the
 * warning-to-landing delay. Both are one more scheduler this module would own next to {@code Scheduling},
 * and the second one is the harder bug: a delayed task is state that lives only in the JVM's heap, so a
 * restart during a warning would either forget the drop was coming or land it early with no warning ever
 * having reached anybody still online. Here, "how long until landing" is measured against
 * {@link de.raindancer.modules.hungergames.service.VirtualTime}'s own elapsed time instead — {@link #tick}
 * is told the current elapsed and lands whatever pending drop has reached its own recorded landing time —
 * so the only state a restart has to recover is a location, exactly what
 * {@link RuntimeStore.SupplyDropState} already persists, and {@link #restoreFromStore} lands those crates
 * immediately rather than resurrecting a countdown nobody can see any more.
 *
 * <h2>Where the timetable and the loot table come from</h2>
 * Neither is a settings key: {@code HungerGamesSettings} has no {@code sponsors}-style list-of-drop-times
 * component, for the same reason the border's phases are not a settings component either — see
 * {@code MODULE-LAYOUT.md}. Until a store for it exists, the timetable arrives as a {@link Timetable}
 * collaborator and the loot table's key arrives per call — a caller that already knows which table backs a
 * drop (today, a fixed key from wiring; later, a store) is the one place that has to know it.
 *
 * <h2>Where the world touches this class, and where it deliberately does not</h2>
 * {@link Arena} is the only seam that needs a loaded world: finding the arena's centre, checking a
 * candidate spot is inside the border and stands on solid, non-liquid ground. {@link Landing} is the only
 * seam that needs to place blocks and play a cue. Everything between them — which drop is due, where a
 * candidate spot falls relative to the centre, how many attempts are spent looking — is ordinary arithmetic
 * and is exactly what {@code SupplyDropServiceTest} exercises without a server.
 */
public final class SupplyDropService implements IHungerGamesService, EventEndpoints.SupplyDrops {

    /** How many candidate spots are tried before a drop gives up and is skipped. */
    public static final int MAX_POSITION_ATTEMPTS = 30;

    /** How many blocks inside the border's edge a candidate spot must fall, on every side. */
    public static final int BORDER_MARGIN = 8;

    /** Where the scheduled drop times come from — see the class note on why this is not a settings key. */
    @FunctionalInterface
    public interface Timetable {
        List<Duration> get();
    }

    /** The one seam that needs a loaded world: finding a centre and judging a candidate spot. */
    public interface Arena {

        /** The arena's centre, or empty before {@code /init} has run. */
        Optional<Location> centre();

        /**
         * The ground at {@code centreX + dx}, {@code centreZ + dz} — one block above the highest solid,
         * non-liquid block — or empty when that spot is liquid, air, outside the current border (by
         * {@link #BORDER_MARGIN}), or in the wrong world for {@code onlyOverworld}.
         */
        Optional<Location> siteAt(int dx, int dz, boolean onlyOverworld);

        /**
         * A loaded world by name, for turning a persisted location back into a real one on restart — the
         * seam that stands in for {@code Bukkit.getWorld}, which this class never calls directly (see the
         * class note on where the world touches this class).
         */
        Optional<World> worldNamed(String name);
    }

    /** The one seam that places anything: the crate, its loot, and whatever marks the landing. */
    public interface Landing {

        /**
         * Fills a chest at {@code site} from {@code lootTableKey} (Core's, via {@code LootCatalogue}) and,
         * per {@code settings}, marks it against being broken, adds a beacon, and plays a firework and/or
         * particles.
         */
        void place(Location site, String lootTableKey, HungerGamesSettings settings);
    }

    /** Somewhere for a drop's story to be written down — see {@code RoundExpiryService.Note} for the twin. */
    @FunctionalInterface
    public interface RoundLog {
        void log(String category, String message, Location location);

        default void log(String category, String message) {
            log(category, message, null);
        }
    }

    private final GameSession session;
    private final VirtualTime virtualTime;
    private final Timetable timetable;
    private final Arena arena;
    private final Landing landing;
    private final AnnouncementService announcements;
    private final Audience broadcastAudience;
    private final RoundLog roundLog;
    private final RuntimeStore runtimeStore;
    private final Random random;
    private final String lootTableKey;

    private final Set<Integer> triggered = new HashSet<>();

    /** Location string (as {@link RuntimeStore} persists it) to the elapsed time it is due to land. */
    private final Map<Location, Duration> pendingLandAt = new LinkedHashMap<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    /**
     * @param broadcastAudience who a landing warning is announced to — the wiring layer's
     *                          {@code Bukkit.getServer()}, kept out of this class so a test can hand in
     *                          anything that implements {@code Audience}
     */
    public SupplyDropService(GameSession session, VirtualTime virtualTime, Timetable timetable, Arena arena,
                              Landing landing, AnnouncementService announcements, Audience broadcastAudience,
                              RoundLog roundLog, RuntimeStore runtimeStore, Random random, String lootTableKey) {
        this.session = session;
        this.virtualTime = virtualTime;
        this.timetable = timetable;
        this.arena = arena;
        this.landing = landing;
        this.announcements = announcements;
        this.broadcastAudience = broadcastAudience;
        this.roundLog = roundLog;
        this.runtimeStore = runtimeStore;
        this.random = random;
        this.lootTableKey = lootTableKey;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== lifecycle ====================

    /** Starts a fresh round with an empty schedule of fired drops. */
    public void start() {
        triggered.clear();
        pendingLandAt.clear();
    }

    /**
     * Lands whatever was still airborne when the server went down, immediately rather than resuming a
     * countdown nobody watching can see any more — see the class note.
     */
    public void restoreFromStore() {
        triggered.addAll(runtimeStore.loadSupplyDropState().triggeredIndices());
        for (String raw : runtimeStore.loadSupplyDropState().pendingLandings()) {
            this.parseLocation(raw).ifPresent(location -> {
                roundLog.log("SUPPLY", "a drop still airborne when the server restarted was landed now",
                        location);
                landing.place(location, lootTableKey, settings);
            });
        }
        persist();
    }

    // ==================== status ====================

    @Override
    public String statusLine() {
        if (!settings.supplyDropsEnabled()) {
            return "disabled";
        }
        return triggered.size() + "/" + timetable.get().size() + " triggered";
    }

    @Override
    public List<EventEndpoints.SupplyDropSlot> schedule() {
        List<Duration> table = timetable.get();
        List<EventEndpoints.SupplyDropSlot> slots = new ArrayList<>(table.size());
        for (int i = 0; i < table.size(); i++) {
            slots.add(new EventEndpoints.SupplyDropSlot(i, table.get(i).toSeconds(), triggered.contains(i)));
        }
        return slots;
    }

    // ==================== tick ====================

    /**
     * One tick of the schedule: begins any drop whose scheduled time has come, and lands any drop whose
     * warning has run out. Idempotent enough to call every second — everything before its own moment
     * returns having done nothing.
     */
    public void tick(Duration elapsed) {
        if (session.phase() != GamePhase.RUNNING || !settings.supplyDropsEnabled()) {
            return;
        }
        for (int index : Schedule.dueIndices(timetable.get(), triggered, elapsed)) {
            triggered.add(index);
            persist();
            roundLog.log("SUPPLY", "scheduled drop #" + (index + 1) + " triggered");
            beginDrop(elapsed);
        }
        landDue(elapsed);
    }

    /**
     * Triggers a drop right now, warning phase included.
     *
     * @return an error message, or empty on success
     */
    @Override
    public Optional<String> triggerNow(String actor) {
        if (session.phase() != GamePhase.RUNNING) {
            return Optional.of("supply drops only run during RUNNING");
        }
        if (!settings.supplyDropsEnabled()) {
            return Optional.of("supply drops are disabled (events.supply-drops.enabled)");
        }
        roundLog.log("SUPPLY", actor + " triggered a drop manually");
        beginDrop(virtualTime.elapsed());
        return Optional.empty();
    }

    private void beginDrop(Duration elapsed) {
        int count = settings.supplyDropCount();
        for (int i = 0; i < count; i++) {
            Optional<Location> target = pickDropLocation();
            if (target.isEmpty()) {
                roundLog.log("SUPPLY", "no suitable landing spot found — a drop was skipped");
                continue;
            }
            Location site = target.get();
            announceWarning(site);
            pendingLandAt.put(site, elapsed.plusSeconds(Math.max(1, settings.supplyDropWarningSeconds())));
            persist();
        }
    }

    private void landDue(Duration elapsed) {
        List<Location> due = pendingLandAt.entrySet().stream()
                .filter(entry -> elapsed.compareTo(entry.getValue()) >= 0)
                .map(Map.Entry::getKey)
                .toList();
        for (Location site : due) {
            pendingLandAt.remove(site);
            persist();
            if (session.phase() == GamePhase.RUNNING) {
                landing.place(site, lootTableKey, settings);
                roundLog.log("SUPPLY", "a drop landed", site);
            }
        }
    }

    // ==================== position ====================

    private Optional<Location> pickDropLocation() {
        int radiusMin = settings.supplyDropRadiusMin();
        int radiusMax = Math.max(radiusMin + 1, settings.supplyDropRadiusMax());
        boolean onlyOverworld = settings.supplyDropOnlyOverworld();
        for (int attempt = 0; attempt < MAX_POSITION_ATTEMPTS; attempt++) {
            int[] offset = candidateOffset(random, radiusMin, radiusMax);
            Optional<Location> site = arena.siteAt(offset[0], offset[1], onlyOverworld);
            if (site.isPresent()) {
                return site;
            }
        }
        return Optional.empty();
    }

    /**
     * A candidate {@code (dx, dz)} offset from the centre, uniformly at a distance between
     * {@code radiusMin} and {@code radiusMax}. Pure, and the reason a test can check the distribution
     * without a world: sampling an angle and a radius is ordinary trigonometry, only judging whether the
     * result is a legal spot needs a server.
     */
    static int[] candidateOffset(Random random, int radiusMin, int radiusMax) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = radiusMin + random.nextDouble() * (radiusMax - radiusMin);
        return new int[]{
                (int) Math.round(Math.cos(angle) * distance),
                (int) Math.round(Math.sin(angle) * distance)
        };
    }

    // ==================== announcements / persistence ====================

    private void announceWarning(Location target) {
        announcements.send(null, broadcastAudience, "supply-drop-warning",
                new AnnouncementService.Style[]{AnnouncementService.Style.CHAT, AnnouncementService.Style.TITLE},
                "seconds", String.valueOf(settings.supplyDropWarningSeconds()),
                "coords", coordsText(target));
    }

    private String coordsText(Location location) {
        if (!settings.supplyDropAnnounceCoordinates()) {
            return "";
        }
        int fuzz = settings.supplyDropCoordinateFuzz();
        int x = location.getBlockX() + (fuzz > 0 ? random.nextInt(fuzz * 2 + 1) - fuzz : 0);
        int z = location.getBlockZ() + (fuzz > 0 ? random.nextInt(fuzz * 2 + 1) - fuzz : 0);
        return " near " + x + " / " + z + (fuzz > 0 ? " (approximately)" : "");
    }

    private void persist() {
        runtimeStore.saveSupplyDropState(new RuntimeStore.SupplyDropState(Set.copyOf(triggered),
                pendingLandAt.keySet().stream().map(SupplyDropService::serialise).toList()));
    }

    private static String serialise(Location location) {
        return location.getWorld().getName() + "," + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    private Optional<Location> parseLocation(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            return Optional.empty();
        }
        World world = arena.worldNamed(parts[0]).orElse(null);
        if (world == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Location(world, Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    @Override
    public String describe() {
        return "timed Capitol supply drops";
    }
}
