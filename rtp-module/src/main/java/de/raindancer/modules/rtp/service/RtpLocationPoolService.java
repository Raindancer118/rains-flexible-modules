package de.raindancer.modules.rtp.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.teleport.Scatter;
import de.raindancer.modules.rtp.RtpSettings;
import de.raindancer.modules.rtp.model.PreparedSpot;
import de.raindancer.modules.rtp.store.RtpLocationRegistry;
import de.raindancer.modules.rtp.store.RtpLocationStorage;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pool of already-checked landings, so most trips do not have to search at all.
 *
 * <h2>What "prepared" does and does not mean</h2>
 * A spot in this pool passed the exact same check a live trip would have — natural ground, a height
 * that agrees with its neighbours, everything {@link Safety#findSafeAtConsistentHeight} already
 * enforces. What it does not mean is that the spot is <em>still</em> good: a tree can grow over it, a
 * player can build on it, a chunk can regenerate under a datapack change, all between it being found
 * and somebody actually being sent there. So {@link #take} checks it again — cheaply, one column, no
 * ring to search — before ever handing it out. A pool that skipped that check would be a pool that
 * occasionally drops somebody in a wall.
 *
 * <h2>Why a spot is not removed once somebody has used it</h2>
 * It is still exactly as good for everybody else. What must not happen is the <em>same</em> player
 * landing there twice by chance — see {@link PreparedSpot#usedBy}, which is what {@link #take} checks
 * instead of removing the spot outright.
 *
 * <h2>Writing</h2>
 * Every change marks the pool dirty and asks for a save off the server thread, the same as the report
 * queue — see {@link RtpLocationStorage}. Locations are small and change constantly (every single trip
 * that uses one marks it used by somebody), so a pool that only reached the disk on shutdown would lose
 * a day of "who has been where" on a crash and hand some of those players a repeat.
 */
public final class RtpLocationPoolService implements IRtpService {

    private static final int FROM_THE_SKY = 40;

    private final Plugin plugin;
    private final Safety safety;
    private final RtpLocationRegistry registry;
    private final RtpLocationStorage storage;
    private final LogChannel log;
    private final Random random;

    private final AtomicBoolean dirty = new AtomicBoolean();

    private volatile RtpSettings settings;

    public RtpLocationPoolService(Plugin plugin, Safety safety, RtpLocationRegistry registry,
                                  RtpLocationStorage storage, LogChannel log, RtpSettings settings,
                                  Random random) {
        this.plugin = plugin;
        this.safety = safety;
        this.registry = registry;
        this.storage = storage;
        this.log = log;
        this.random = random == null ? new Random() : random;
        settings(settings);
    }

    @Override
    public void settings(RtpSettings fresh) {
        this.settings = fresh == null ? RtpSettings.DEFAULTS : fresh;
    }

    /** Reads what is on disk into the pool. Called once, when the module starts. */
    public void load() {
        registry.clear();
        for (PreparedSpot spot : storage.load()) {
            registry.add(spot);
        }
    }

    /** How many are ready right now, across every world. */
    public int size() {
        return registry.size();
    }

    // ---------------------------------------------------------------------------- handing one out

    /**
     * Somewhere already checked for this player, or empty when the pool has nothing left for them —
     * which a caller must treat as "search live instead", not as "nowhere is safe".
     */
    public CompletableFuture<Optional<Location>> take(UUID player, World world) {
        if (!settings.poolEnabled() || safety == null || player == null || world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<PreparedSpot> candidates = registry.availableFor(player, world.getName());
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        shuffle(candidates);
        return verifyNext(candidates, 0, player, world);
    }

    /**
     * Tries each candidate in turn, nearest problem first: a spot the ground has moved under is
     * dropped from the pool for good — it did not become good again by being asked about twice — and
     * the next one is tried in its place.
     */
    private CompletableFuture<Optional<Location>> verifyNext(List<PreparedSpot> candidates, int index,
                                                              UUID player, World world) {
        if (index >= candidates.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        PreparedSpot candidate = candidates.get(index);
        return safety.findSafeAtConsistentHeight(candidate.spot(), 0, settings.tolerance(),
                        spots -> spots.naturalGroundOnly(true))
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        registry.remove(candidate.id());
                        changed();
                        return verifyNext(candidates, index + 1, player, world);
                    }
                    registry.markUsed(candidate.id(), player);
                    changed();
                    Spot verified = found.get();
                    return CompletableFuture.completedFuture(Optional.of(
                            new Location(world, verified.centreX(), verified.y(), verified.centreZ())));
                });
    }

    private void shuffle(List<PreparedSpot> candidates) {
        java.util.Collections.shuffle(candidates, random);
    }

    // ---------------------------------------------------------------------------- preparing

    /**
     * Searches for {@code amount} more, stopping early once the pool is full — see
     * {@link RtpSettings#maxPoolSize()}.
     *
     * @return how many were actually added, once every search has answered
     */
    public CompletableFuture<Integer> prepare(World world, int amount) {
        if (world == null || amount <= 0 || safety == null) {
            return CompletableFuture.completedFuture(0);
        }
        return prepareStep(world, amount, 0);
    }

    private CompletableFuture<Integer> prepareStep(World world, int remaining, int done) {
        if (remaining <= 0 || registry.size() >= settings.maxPoolSize()) {
            return CompletableFuture.completedFuture(done);
        }
        return prepareOne(world).thenCompose(added ->
                prepareStep(world, remaining - 1, added ? done + 1 : done));
    }

    private CompletableFuture<Boolean> prepareOne(World world) {
        RtpSettings snapshot = settings;
        Scatter.Point point = snapshot.scatterWithin(world).pick(random);
        Location centre = world.getSpawnLocation();
        double top = Math.max(64, world.getMaxHeight() - FROM_THE_SKY);
        Location raw = new Location(world, centre.getX() + point.x() + 0.5, top,
                centre.getZ() + point.z() + 0.5);
        Spot around = de.raindancer.core.world.teleport.Travel.spotOf(raw);

        return safety.findSafeAtConsistentHeight(around, snapshot.arrivalRadius(), snapshot.tolerance(),
                        spots -> spots.naturalGroundOnly(true))
                .thenApply(found -> {
                    if (found.isEmpty()) {
                        return false;
                    }
                    Spot spot = found.get();
                    registry.add(new PreparedSpot(registry.nextId(), spot.world(), spot.x(), spot.y(),
                            spot.z(), Instant.now(), java.util.Set.of()));
                    changed();
                    return true;
                });
    }

    /**
     * Tops every world this runs in up to the daily minimum, once — the job the scheduled timer calls.
     *
     * <p>Reads the settings fresh each time rather than once at startup, so switching the pool off, or
     * changing the minimum, through {@code /settings} takes effect on the very next day without a
     * restart.
     */
    public void topUpAllWorlds(Server server) {
        RtpSettings snapshot = settings;
        if (server == null || safety == null || !snapshot.poolEnabled() || snapshot.dailyMinimum() <= 0) {
            return;
        }
        for (World world : server.getWorlds()) {
            if (snapshot.isDisabled(world.getName())) {
                continue;
            }
            prepare(world, snapshot.dailyMinimum()).thenAccept(added -> {
                if (added > 0 && log != null) {
                    log.info("Prepared {} more random-teleport location(s) in {}.", added,
                            world.getName());
                }
            });
        }
    }

    // ---------------------------------------------------------------------------- writing

    /** Marks the pool as needing writing, and asks for it off the server thread. */
    private void changed() {
        dirty.set(true);
        Scheduling.async(plugin, this::flush);
    }

    /**
     * Writes if anything has changed. <b>Synchronised</b>, so two flushes cannot overlap — see
     * {@code ReportService#flush} for why.
     */
    public synchronized boolean flush() {
        if (!dirty.getAndSet(false)) {
            return false;
        }
        if (!storage.saveAll(registry.snapshot())) {
            dirty.set(true);
            return false;
        }
        return true;
    }

    /** Writes whatever is held, changed or not. For a shutdown, which has no next pass. */
    public synchronized boolean flushNow() {
        dirty.set(false);
        return storage.saveAll(registry.snapshot());
    }

    @Override
    public String describe() {
        return "a pool of already-checked random-teleport landings, prepared ahead of time";
    }
}
