package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Schedule;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Sponsor beacons: physical shop stations placed in the arena. Together with {@link SponsorTokenService}
 * this satisfies {@link EventEndpoints.Sponsors} — tokens for everything about the currency, this class
 * for everything about where it is spent; the split mirrors the source engine's own two services and keeps
 * either half testable without the other.
 *
 * <h2>Restart safety</h2>
 * Active beacon locations and which random-spawn slots have already fired are exactly the shape of fact
 * {@link RuntimeStore} exists to carry across a restart — see its class note, and
 * {@code RuntimeStore.SupplyDropState}'s twin for supply drops. {@link #start} reloads both from
 * {@link RuntimeStore#loadSponsorBeaconState()}; every mutation here — {@link #createBeacon},
 * {@link #removeBeacon}, {@link #removeAllBeacons}, a random-timed spawn in {@link #tick} — persists before
 * returning, the same discipline {@code GameSession} holds itself to for the round.
 *
 * <h2>What is not here</h2>
 * The interaction that opens a shop menu at a beacon, and the block-break protection around it, are a
 * listener's job — every service in this wave is driven by explicit calls rather than by implementing
 * {@code Listener} itself (see {@code BorderService}, {@code DeathmatchService}). {@link #isSponsorBeacon}
 * and {@link #createBeacon}/{@link #removeBeacon} are the query and the actions a listener elsewhere would
 * call; wiring the actual {@code PlayerInteractEvent} and {@code BlockBreakEvent} handlers is outside this
 * class's lane.
 */
public final class SponsorBeaconService implements IHungerGamesService, EventEndpoints.Sponsors {

    public static final int MAX_POSITION_ATTEMPTS = 30;
    public static final int BORDER_MARGIN = 8;

    /** Where a random-timed beacon's schedule comes from — see {@code SupplyDropService.Timetable}'s twin. */
    @FunctionalInterface
    public interface Timetable {
        List<Duration> get();
    }

    /** The one seam that needs a loaded world. */
    public interface Arena {
        Optional<Location> centre();

        /** A candidate spot at {@code centreX + dx}, {@code centreZ + dz}, or empty if it will not do. */
        Optional<Location> siteAt(int dx, int dz);

        /** A loaded world by name, for turning a persisted location back into a real one on restart. */
        Optional<World> worldNamed(String name);
    }

    /** Placing or removing the physical beacon block and its base — the one seam that touches the world. */
    public interface BeaconBlock {
        void place(Location site, HungerGamesSettings settings);

        void remove(Location site);
    }

    @FunctionalInterface
    public interface RoundLog {
        void log(String category, String message, Location location);

        default void log(String category, String message) {
            log(category, message, null);
        }
    }

    private final GameSession session;
    private final Arena arena;
    private final BeaconBlock block;
    private final AnnouncementService announcements;
    private final Audience broadcastAudience;
    private final RoundLog roundLog;
    private final RuntimeStore runtimeStore;
    private final SponsorTokenService tokens;
    private final Random random;

    private final List<Location> beacons = new ArrayList<>();
    private final Set<Integer> triggeredSpawns = new HashSet<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    /**
     * @param broadcastAudience who a beacon's spawn is announced to — see
     *                          {@code SupplyDropService}'s constructor note on why this is a parameter
     *                          rather than a call to {@code Bukkit.getServer()} made from inside this class
     */
    public SponsorBeaconService(GameSession session, Arena arena, BeaconBlock block,
                                 AnnouncementService announcements, Audience broadcastAudience,
                                 RoundLog roundLog, RuntimeStore runtimeStore, SponsorTokenService tokens,
                                 Random random) {
        this.session = session;
        this.arena = arena;
        this.block = block;
        this.announcements = announcements;
        this.broadcastAudience = broadcastAudience;
        this.roundLog = roundLog;
        this.runtimeStore = runtimeStore;
        this.tokens = tokens;
        this.random = random;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== lifecycle ====================

    /** Reloads persisted beacon locations and fired spawn slots after a restart. */
    public void start() {
        beacons.clear();
        triggeredSpawns.clear();
        RuntimeStore.SponsorBeaconState state = runtimeStore.loadSponsorBeaconState();
        for (String raw : state.locations()) {
            parseLocation(raw).ifPresent(beacons::add);
        }
        triggeredSpawns.addAll(state.triggeredSpawns());
    }

    // ==================== EventEndpoints.Sponsors — tokens, delegated ====================

    @Override
    public boolean tokensEnabled() {
        return tokens.tokensEnabled();
    }

    @Override
    public void giveManually(String actor, Player target, int amount) {
        tokens.giveManually(actor, target, amount);
    }

    @Override
    public int clearTokens(Player target) {
        return tokens.clearTokens(target);
    }

    // ==================== EventEndpoints.Sponsors — beacons ====================

    @Override
    public boolean beaconsEnabled() {
        // Beacons as their own switch have no settings home yet — see the class javadoc on SponsorTokenService
        // for why; a caller wanting them off can simply never call spawnRandom/createBeacon.
        return true;
    }

    @Override
    public List<Location> activeBeacons() {
        return List.copyOf(beacons);
    }

    @Override
    public String statusLine() {
        return beacons.size() + " active";
    }

    @Override
    public Optional<String> createBeacon(Location location, String actor) {
        if (location.getWorld() == null) {
            return Optional.of("no valid location");
        }
        block.place(location, settings);
        // Snapped to whole-block coordinates without going through Location.getBlock(), which would ask
        // the world for the block at that position — this registry only needs the coordinate, not a live
        // Block handle, and a test should not need a mocked World to answer getBlockAt().
        beacons.add(new Location(location.getWorld(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ()));
        persist();
        announcements.send(null, broadcastAudience, "sponsor-beacon-spawned",
                new AnnouncementService.Style[]{AnnouncementService.Style.CHAT,
                        AnnouncementService.Style.TITLE},
                "coords", coordsText(location));
        roundLog.log("SPONSOR", "sponsor beacon created by " + actor, location);
        return Optional.empty();
    }

    public void removeBeacon(Location location, String actor) {
        beacons.remove(location);
        persist();
        block.remove(location);
        roundLog.log("SPONSOR", "sponsor beacon removed by " + actor, location);
    }

    @Override
    public int removeAllBeacons(String actor) {
        int count = beacons.size();
        for (Location location : List.copyOf(beacons)) {
            removeBeacon(location, actor);
        }
        return count;
    }

    /** Whether a block is one of the registry's active beacons — the query a listener elsewhere would use. */
    public boolean isSponsorBeacon(Location location) {
        return beacons.contains(location);
    }

    // ==================== random-timed spawns ====================

    /** One tick of the random-spawn schedule; a no-op outside RUNNING or with an empty timetable. */
    public void tick(Duration elapsed, Timetable timetable) {
        if (session.phase() != GamePhase.RUNNING) {
            return;
        }
        for (int index : Schedule.dueIndices(timetable.get(), triggeredSpawns, elapsed)) {
            triggeredSpawns.add(index);
            persist();
            spawnRandom("System (scheduled)");
        }
    }

    /** Spawns one beacon near the centre once the round starts — the "CENTER" spawn mode. */
    public void spawnAtCentre(int offset) {
        arena.centre().ifPresent(centre -> arena.siteAt(offset, offset)
                .ifPresent(site -> createBeacon(site, "System (centre)")));
    }

    private void spawnRandom(String actor) {
        int radiusMin = 20;
        int radiusMax = 80;
        for (int attempt = 0; attempt < MAX_POSITION_ATTEMPTS; attempt++) {
            int[] offset = SupplyDropService.candidateOffset(random, radiusMin, radiusMax);
            Optional<Location> site = arena.siteAt(offset[0], offset[1]);
            if (site.isPresent()) {
                createBeacon(site.get(), actor);
                return;
            }
        }
        roundLog.log("SPONSOR", "a scheduled beacon spawn found no suitable spot");
    }

    // ==================== reset ====================

    public void resetForNewRound() {
        beacons.clear();
        triggeredSpawns.clear();
        persist();
    }

    // ==================== persistence ====================

    private void persist() {
        runtimeStore.saveSponsorBeaconState(new RuntimeStore.SponsorBeaconState(
                beacons.stream().map(SponsorBeaconService::serialise).toList(), Set.copyOf(triggeredSpawns)));
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

    // ==================== internal ====================

    private String coordsText(Location location) {
        return " near " + location.getBlockX() + " / " + location.getBlockZ();
    }

    @Override
    public String describe() {
        return "sponsor beacons — where sponsor tokens are spent";
    }
}
