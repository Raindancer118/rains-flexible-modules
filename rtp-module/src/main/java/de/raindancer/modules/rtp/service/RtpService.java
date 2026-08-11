package de.raindancer.modules.rtp.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.teleport.Scatter;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.rtp.RtpSettings;
import de.raindancer.modules.rtp.rules.RtpRule;
import de.raindancer.modules.rtp.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Sending somebody somewhere random in their own world.
 *
 * <h2>What is here and what is Core's</h2>
 * The waiting, the movement cancelling and the teleport itself are all {@link Travel}'s — the same
 * code the warps, the homes and the teleport requests use. The ring somebody lands in is Core's
 * {@link Scatter}, the wait between goes is Core's {@link Cooldowns}, and the search for solid ground
 * is Core's {@link Safety}. What is left here, and it is the whole job of this class, is the order the
 * questions are asked in, where the ring is centred, whether this trip is checked at all, and what
 * each answer is worded as.
 *
 * <h2>Why the search runs before {@code Travel.go} rather than through {@link Trip#searching}</h2>
 * Two reasons, and both are new since the plain version of this class. First, whether a landing is
 * checked at all is a per-trip decision — see {@link RtpSettings#safeArrivalPolicy()} — and
 * {@code Trip} has no "maybe" for that, only on or off. Second, the search this module wants is
 * {@link Safety#findSafeAtConsistentHeight}, not the plain one {@code Travel} calls internally, and
 * there is no way to hand {@code Travel} a different search without forking its warm-up and
 * move-cancelling machinery alongside it — which is exactly the second copy {@code ReuseTest} exists
 * to catch.
 *
 * <p>So this module does its own search first and hands {@code Travel} an already-resolved
 * destination with {@link Trip#exactly()}, which turns out to read better than the original order
 * besides: nobody stands still waiting out a warm-up for a search that was always going to say no.
 *
 * <h2>Why the wait is charged on arrival and never up front</h2>
 * Charging first means refunding whenever a warm-up is interrupted, and a refund is a blunt clear of
 * the wait that takes whatever else was on it. So: ask, travel, record when they actually get there.
 * Being knocked out of a warm-up by a zombie costs nothing, and neither does a search that found
 * nowhere safe to land.
 *
 * <h2>Where the column comes from, and why it points at the sky</h2>
 * {@link Scatter} hands back an offset on the flat; this turns that into a point near the top of the
 * world. Checked, Core's safety search brings it down onto the ground — looking up and down the whole
 * column from wherever it is pointed is what makes it land on the surface rather than, on a column
 * with a cave and a hilltop, in the cave. <b>Unchecked</b>, that same high point is exactly where the
 * player is sent — which is the point: a random teleport with the safety net off drops somebody from
 * the sky and lets gravity decide, void included. That is not a bug this module tries to prevent.
 *
 * <h2>Why a checked landing asks for natural ground only</h2>
 * Nobody scattered at random chose the tree they landed at the top of; that is just the first solid
 * thing the search happened to fall onto. {@link Safety#findSafeAtConsistentHeight} is handed a
 * setup that turns on {@code naturalGroundOnly}, so a checked trip only ever counts stone, dirt,
 * sand and the rest of the terrain itself — never a log, a leaf, a roof or a bridge. A warp somebody
 * placed on a platform on purpose is a different question with a different answer, which is exactly
 * why that flag defaults to off everywhere else and is only turned on here.
 */
public final class RtpService implements IRtpService {

    private static final int FROM_THE_SKY = 40;

    /**
     * How many different random points a checked trip tries before actually giving up.
     *
     * <p>"Nowhere safe within the radius" is a fact about the one point that was picked, not about
     * the world — a different roll a moment later can land somewhere with room to search in easily.
     * Bounded rather than endless so a world genuinely without anywhere to land (a radius pinned
     * entirely over one lake, say) still answers in a few searches instead of hanging a player's
     * command on a point that was never going to work.
     */
    private static final int MAX_SEARCH_ATTEMPTS = 3;

    /** How long the "still searching" action bar is shown for at a time. Refreshed while it waits. */
    private static final java.time.Duration SEARCHING_NOTICE_LIFETIME = java.time.Duration.ofSeconds(3);

    private final Plugin plugin;
    private final Travel travel;
    private final Safety safety;
    private final RtpLocationPoolService pool;
    private final RtpRule rule;
    private final Messages messages;
    private final Effects effects;
    private final ActionBars actionBars;
    private final LogChannel log;
    private final Random random;

    /**
     * The wait between goes.
     *
     * <p>Core's, keyed by player. Not a map of "when did this player last go" — the check-then-record
     * version of that had been written five times across this repository already, and every copy could
     * let two clicks in the same millisecond both through.
     */
    private final Cooldowns<UUID> between = new Cooldowns<>();

    private volatile RtpSettings settings;

    /**
     * @param safety null on a host without Core's chunk holds — a checked trip then behaves as an
     *               unchecked one rather than refusing outright
     * @param random passed in rather than made, so a test can hand in a known sequence. Not seeded
     *               from anything about the world: two players asking in the same second must not
     *               land in the same place, and a seed derived from the world would do exactly that
     */
    public RtpService(Plugin plugin, Travel travel, Safety safety, RtpLocationPoolService pool,
                      RtpRule rule, Messages messages, Effects effects, ActionBars actionBars,
                      LogChannel log, RtpSettings settings, Random random) {
        this.plugin = plugin;
        this.travel = travel;
        this.safety = safety;
        this.pool = pool;
        this.rule = rule;
        this.messages = messages;
        this.effects = effects;
        this.actionBars = actionBars;
        this.log = log;
        this.random = random == null ? new Random() : random;
        settings(settings);
    }

    @Override
    public void settings(RtpSettings fresh) {
        this.settings = fresh == null ? RtpSettings.DEFAULTS : fresh;
        // Pushed into the cooldown, which holds its own copy of the wait. Left out, the file says
        // thirty seconds and the server keeps enforcing whatever it said when this started.
        between.every(java.time.Duration.ofSeconds(this.settings.cooldown()));
    }

    /** The wait, for a screen or a diagnostic that wants to say how long is left. */
    public Cooldowns<UUID> waits() {
        return between;
    }

    /** Lets go of a player's wait. Called when they leave — see {@code RtpSessionListener}. */
    public void forget(UUID who) {
        between.forget(who);
    }

    /** Whether this player's own choice is even asked for, under the settings right now. */
    public boolean playerMayChoose() {
        return settings.safeArrivalPolicy() == de.raindancer.core.world.protection.FlagPolicy.AVAILABLE;
    }

    // ------------------------------------------------------------------------ going

    /** The same as {@link #go(Player, boolean, Integer)}, with no minimum distance asked for. */
    public void go(Player traveller, boolean playerWantsSafe) {
        go(traveller, playerWantsSafe, null);
    }

    /**
     * Sends this player somewhere random in the world they are standing in, or tells them why not.
     *
     * <p>Every path out of here says something. A command that silently does nothing is one people
     * type four more times and then report as broken.
     *
     * @param playerWantsSafe what this player asked for this trip. Honoured only when the settings
     *                        say {@code AVAILABLE} — see {@link RtpRule#effectiveSafeArrival}
     * @param minDistance     how close to the middle this one trip refuses to land, overriding the
     *                        owner's own minimum for this trip only; null asks for nothing beyond what
     *                        the settings already say. The pool is skipped when this is asked for — a
     *                        spot prepared ahead of time was found without knowing anybody would ask
     *                        for this, so it cannot promise it
     */
    public void go(Player traveller, boolean playerWantsSafe, Integer minDistance) {
        if (traveller == null) {
            return;
        }
        World world = traveller.getWorld();
        Verdict allowed = rule.mayGo(world == null ? null : world.getName(), settings.disabledWorlds());
        if (allowed.isRefused()) {
            messages.send(traveller, allowed.reason(), "world", allowed.detail());
            refused(traveller);
            return;
        }

        // Asked, not spent — see the class note on why the wait is charged on arrival.
        if (!traveller.hasPermission(PermissionNodes.BYPASS_COOLDOWN)
                && !between.isReady(traveller.getUniqueId())) {
            messages.send(traveller, "rtp.on-cooldown", "time", waitLeft(traveller.getUniqueId()));
            play(traveller, Cues.COOLDOWN);
            return;
        }

        Location raw = destinationIn(world, traveller, minDistance);
        boolean checked = rule.effectiveSafeArrival(settings.safeArrivalPolicy(), playerWantsSafe);
        if (!checked || safety == null) {
            depart(traveller, raw);
            return;
        }

        if (pool == null || minDistance != null) {
            searchLive(traveller, raw, minDistance, 1);
            return;
        }
        // The pool first: a spot already found and checked, re-verified once more because the ground
        // under it can have changed since — see RtpLocationPoolService. Empty means the pool had
        // nothing left this player has not already been sent to, which is a reason to search live,
        // not a reason to refuse the trip.
        pool.take(traveller.getUniqueId(), world)
                .thenAccept(fromPool -> fromPool.ifPresentOrElse(
                        location -> onThePlayersThread(traveller, () -> {
                            if (traveller.isOnline()) {
                                depart(traveller, location);
                            }
                        }),
                        () -> searchLive(traveller, raw, null, 1)))
                .exceptionally(failure -> {
                    searchLive(traveller, raw, null, 1);
                    return null;
                });
    }

    /**
     * The search this module always did before there was a pool to try first.
     *
     * <p>A point with nothing safe near it is a fact about that one roll of the dice, not about the
     * world — so rather than tell a player no and make them type the command again, this quietly
     * rolls again itself, up to {@link #MAX_SEARCH_ATTEMPTS} times, with a fresh point of its own each
     * time. Only the last attempt's failure is ever actually shown to anybody.
     */
    private void searchLive(Player traveller, Location raw, Integer minDistance, int attempt) {
        // The search is bounded to a couple of seconds even on bad terrain — see
        // SafeSpots#nearestConsistentHeight — but a couple of seconds of nothing happening still
        // reads as a frozen command. Shown at LOW priority: a refusal or an arrival either one
        // takes over the bar the moment it has something to say, which is why every path out of
        // the search below clears this first rather than leaving it to expire on its own.
        showSearching(traveller);

        Spot around = Travel.spotOf(raw);
        // Natural ground only: nobody scattered at random meant to land at the top of a tree, and
        // there is nobody who placed this spot on purpose to overrule — see SafeSpots#naturalGroundOnly.
        safety.findSafeAtConsistentHeight(around, settings.arrivalRadius(), settings.tolerance(),
                        spots -> spots.naturalGroundOnly(true))
                .thenAccept(found -> onThePlayersThread(traveller, () -> {
                    if (found.isPresent()) {
                        clearSearching(traveller);
                        if (traveller.isOnline()) {
                            depart(traveller, at(found.get(), raw));
                        }
                        return;
                    }
                    if (attempt < MAX_SEARCH_ATTEMPTS && traveller.isOnline()) {
                        Location again = destinationIn(traveller.getWorld(), traveller, minDistance);
                        searchLive(traveller, again, minDistance, attempt + 1);
                        return;
                    }
                    clearSearching(traveller);
                    if (traveller.isOnline()) {
                        messages.send(traveller, "rtp.nowhere-safe");
                        refused(traveller);
                    }
                })).exceptionally(failure -> {
                    if (log != null) {
                        log.warn("Could not check whether a random point was safe: {}",
                                failure.toString());
                    }
                    onThePlayersThread(traveller, () -> {
                        clearSearching(traveller);
                        if (traveller.isOnline()) {
                            messages.send(traveller, "rtp.could-not-check");
                            refused(traveller);
                        }
                    });
                    return null;
                });
    }

    /** "Still looking…", so a search that can take a couple of seconds does not read as a freeze. */
    private void showSearching(Player traveller) {
        if (actionBars == null) {
            return;
        }
        actionBars.show(traveller.getUniqueId(), "rtp", messages.get("rtp.searching"),
                SEARCHING_NOTICE_LIFETIME, ActionBarPriority.LOW);
    }

    private void clearSearching(Player traveller) {
        if (actionBars != null) {
            actionBars.clear(traveller.getUniqueId(), "rtp");
        }
    }

    /** The already-resolved destination is handed to Travel with no further search of its own. */
    private void depart(Player traveller, Location destination) {
        int warmup = traveller.hasPermission(PermissionNodes.BYPASS_WARMUP) ? 0 : settings.warmup();
        Trip trip = Trip.to("somewhere new").after(warmup).exactly();
        travel.go(traveller, destination, trip, new Wording());
    }

    /** Runs something back on the thread that owns this player. Never blocks; logs what it throws. */
    private void onThePlayersThread(Player traveller, Runnable task) {
        Scheduling.entity(plugin, traveller, () -> {
            try {
                task.run();
            } catch (RuntimeException thrown) {
                if (log != null) {
                    log.warn("A random teleport for {} failed: {}", traveller.getName(),
                            thrown.toString());
                }
            }
        });
    }

    /**
     * A point near the top of the world, offset from the middle by the configured ring.
     *
     * <p>The middle is the world's own spawn unless the owner asked for it to be wherever the player is
     * standing — see {@link RtpSettings#centreOnPlayer()}.
     */
    /**
     * @param minDistance overrides how close to the middle this point may be, for this call only;
     *                     null takes whatever the settings already say
     */
    private Location destinationIn(World world, Player traveller, Integer minDistance) {
        Location centre = settings.centreOnPlayer() ? traveller.getLocation() : world.getSpawnLocation();
        Scatter base = settings.scatterWithin(world);
        // Built from the settings' own furthest, already kept inside the border — only the nearest
        // changes, and Scatter's own compact constructor sorts out a distance further out than that.
        Scatter scatter = minDistance == null ? base : new Scatter(true, minDistance, base.furthest());
        Scatter.Point point = scatter.pick(random);
        double top = Math.max(64, world.getMaxHeight() - FROM_THE_SKY);
        return new Location(world, centre.getX() + point.x() + 0.5, top,
                centre.getZ() + point.z() + 0.5);
    }

    /**
     * A verified spot, as somewhere to actually put a player — the middle of the block, facing the
     * way the original point faced.
     */
    private static Location at(Spot spot, Location facingLike) {
        World world = org.bukkit.Bukkit.getWorld(spot.world());
        if (world == null) {
            return facingLike;
        }
        return new Location(world, spot.centreX(), spot.y(), spot.centreZ(),
                facingLike.getYaw(), facingLike.getPitch());
    }

    private void play(Player traveller, String cue) {
        if (effects != null && traveller != null) {
            effects.play(traveller.getUniqueId(), cue);
        }
    }

    private void refused(Player traveller) {
        play(traveller, Cues.NO);
    }

    private String waitLeft(UUID traveller) {
        return between.remaining(traveller).map(Times::describe).orElse("a moment");
    }

    /**
     * What the player is told at each point of the journey.
     *
     * <p>The wording is here rather than in Core because "Off to somewhere new in 3…" is this server's
     * sentence, and a library that wrote it would be a library deciding what the server sounds like.
     */
    private final class Wording implements TravelWatcher {

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "rtp.warming-up", "seconds", secondsLeft);
            play(traveller, secondsLeft <= 1 ? Cues.COUNTDOWN_DONE : Cues.COUNTDOWN);
        }

        /**
         * Charged here, and nowhere else. See the class note on why.
         *
         * <p>They are also told where they came out. Not decoration: it is the one thing that makes a
         * random arrival legible rather than disorienting, and it is what somebody types into a message
         * to a friend who wants to meet them.
         */
        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            between.start(traveller.getUniqueId());
            play(traveller, Cues.TELEPORT);
            messages.send(traveller, "rtp.arrived",
                    "where", where.getBlockX() + ", " + where.getBlockZ());
            // One more prepared for the next person, so the pool keeps pace with how often it is
            // actually being spent rather than only catching up once a day — see
            // RtpLocationPoolService#afterATrip.
            if (pool != null) {
                pool.afterATrip(traveller.getWorld());
            }
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why));
            play(traveller, Cues.NO);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why));
            play(traveller, Cues.NO);
        }
    }

    /**
     * The wording key for each reason a trip did not happen.
     *
     * <p>A switch with no default, so that a reason added to {@code TravelReason} is a compiler error
     * here rather than a line of English nobody notices has gone untranslated.
     */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "rtp.cancelled.moved";
            case HURT -> "rtp.cancelled.hurt";
            case ALREADY_TRAVELLING -> "rtp.already-travelling";
            case WORLD_MISSING -> "rtp.world-missing";
            case NOWHERE_SAFE -> "rtp.nowhere-safe";
            case COULD_NOT_CHECK -> "rtp.could-not-check";
            case TELEPORT_REFUSED -> "rtp.teleport-refused";
            case CANNOT_SCHEDULE -> "rtp.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "sending somebody somewhere random in their own world";
    }
}
