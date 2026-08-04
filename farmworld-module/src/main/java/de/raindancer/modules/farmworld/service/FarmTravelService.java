package de.raindancer.modules.farmworld.service;

import de.raindancer.core.world.time.Times;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.model.Scatter;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.store.FarmWorldCatalogue;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Random;
import java.util.UUID;

/**
 * Sending somebody into a farm world.
 *
 * <h2>What is here and what is Core's</h2>
 * The waiting, the movement cancelling, finding somewhere safe to land and the teleport itself are all
 * {@link Travel}'s — the same code the warps, the homes and the teleport requests use. The wait between
 * trips is Core's {@link Cooldowns}. What is left here, and it is the whole job of this class, is the
 * order the questions are asked in, where in the world somebody comes out, and what each answer is
 * worded as.
 *
 * <h2>The order, and why it is that order</h2>
 * Permission, then whether the world is loaded, then the wait. The wait is asked <em>last</em> on
 * purpose: asking it first means a typo, a farm world that is not loaded, or one somebody may not enter
 * each cost a minute of waiting for a trip that never happened.
 *
 * <h2>Why the wait is charged on arrival and never up front</h2>
 * Charging first means refunding whenever a warm-up is interrupted, and a refund is a blunt clear of the
 * wait that takes whatever else was on it. So: ask, travel, record when they actually get there. Being
 * knocked out of a warm-up by a zombie costs nothing, and — the case that matters here — neither does a
 * scatter that found nowhere safe to land, which is otherwise a minute's wait for a refusal.
 */
public final class FarmTravelService implements IFarmWorldService {

    /**
     * How high up the search for solid ground starts.
     *
     * <p>Core looks up and down the whole column from wherever it is pointed and takes the standing spot
     * nearest that height, so pointing it at the sky is what makes it come down onto the surface. Pointed
     * at sea level instead, a column with a cave at thirty and a hilltop at ninety puts the player in the
     * cave — which on arrival in a strange world is indistinguishable from being buried.
     */
    private static final int FROM_THE_SKY = 40;

    private final FarmWorldCatalogue catalogue;
    private final Travel travel;
    private final FarmAccessRule access;
    private final Messages messages;

    /**
     * The sounds, asked for by meaning rather than by name.
     *
     * <p>Core's, so a server that decides an arrival should sound different rebinds one cue and every plugin that
     * sends somebody anywhere changes with it. Null-tolerant on purpose: a missing sound must never be the reason
     * a trip does not happen.
     */
    private final Effects effects;

    /**
     * The wait between trips.
     *
     * <p>Core's, keyed by player. Not a map of "when did this player last go" — the check-then-record
     * version of that had been written five times in this repository and every copy could let two clicks
     * in the same millisecond both through.
     */
    private final Cooldowns<UUID> between = new Cooldowns<>();

    /**
     * Where the next arrival lands.
     *
     * <p>Held rather than made per trip so that a test can hand in a known sequence. Not seeded from the
     * world's seed on purpose: two players arriving in the same farm world in the same second should not
     * land in the same place, and a seed derived from anything about the world is how they would.
     */
    private final Random random;

    private volatile FarmWorldSettings settings;

    public FarmTravelService(FarmWorldCatalogue catalogue, Travel travel, FarmAccessRule access,
                             Messages messages, Effects effects, FarmWorldSettings settings,
                             Random random) {
        this.catalogue = catalogue;
        this.travel = travel;
        this.access = access;
        this.messages = messages;
        this.effects = effects;
        this.random = random == null ? new Random() : random;
        settings(settings);
    }

    @Override
    public void settings(FarmWorldSettings fresh) {
        this.settings = fresh;
        // Pushed into the cooldown, which holds its own copy of the wait. Left out, the file says two
        // minutes and the server keeps enforcing yesterday's one.
        between.every(fresh.cooldownFor());
    }

    /** The wait, for a screen that wants to say how long is left. */
    public Cooldowns<UUID> waits() {
        return between;
    }

    /** Lets go of a player's wait. Called when they leave — see {@code FarmSessionListener}. */
    public void forget(UUID who) {
        between.forget(who);
    }

    // ------------------------------------------------------------------------ going

    /**
     * Sends this player into the farm world of this name, or tells them why not.
     *
     * <p>Every path out of here says something. A command that silently does nothing is one people type
     * four more times, and then report as broken.
     */
    public void goTo(Player traveller, String name) {
        if (traveller == null) {
            return;
        }
        FarmWorldView farm = catalogue.byName(name).orElse(null);
        if (farm == null) {
            messages.send(traveller, "farmworlds.unknown", "name", String.valueOf(name));
            return;
        }
        go(traveller, farm);
    }

    /** The same, when the farm world is already in hand — what a menu click has. */
    public void go(Player traveller, FarmWorldView farm) {
        if (traveller == null || farm == null) {
            return;
        }
        String refusal = access.refusalKey(farm.name(), traveller::hasPermission);
        if (refusal != null) {
            // The rule's own wording key, so the two reasons somebody may not go — no farm worlds at
            // all, or not this one — stay two answers rather than becoming one vague line.
            messages.send(traveller, refusal, "name", farm.name());
            refused(traveller);
            return;
        }
        World world = catalogue.overworldOf(farm.name()).orElse(null);
        if (world == null) {
            // Not a fault: a server that unloads worlds for maintenance has a farm world nobody can
            // enter for a few minutes, and it works again when the world comes back. Nothing has been
            // charged, so there is nothing to give back.
            messages.send(traveller, "farmworlds.not-loaded", "name", farm.name());
            refused(traveller);
            return;
        }

        // Asked, not spent. See the note on the class for why the wait is charged on arrival.
        if (!between.isReady(traveller.getUniqueId())) {
            messages.send(traveller, "farmworlds.on-cooldown",
                    "time", waitLeft(traveller.getUniqueId()));
            // The cooldown cue rather than the flat refusal, because it is a different thing: they may go, just
            // not yet. Core keeps them apart so a player learns which is which without reading.
            play(traveller, Cues.COOLDOWN);
            return;
        }
        depart(traveller, farm, world);
    }

    private void depart(Player traveller, FarmWorldView farm, World world) {
        FarmWorldSettings now = settings;
        Location target = arrivalIn(world, farm, now);
        Trip trip = Trip.to(farm.name())
                .after(now.warmup())
                // Never .exactly(): a scattered point is a point nobody has looked at, and dropping
                // somebody into one unchecked is dropping them inside stone about as often as onto
                // grass. There is deliberately no setting for this — see FarmWorldSettings.
                .searching(now.arrivalRadius())
                .bringing(now.companions());
        travel.go(traveller, target, trip, new Wording(farm));
    }

    /**
     * Where in the farm world this trip comes out.
     *
     * <p>The world's own spawn when scattering is off or the world is too small to scatter in; otherwise
     * a point in the ring, high up, for Core to bring down onto the ground.
     *
     * <p>The border is read off the farm world's definition rather than off the loaded world, because
     * that is the number an owner set — a world whose border has been widened by another plugin at
     * runtime is not an invitation to send people past the one written down.
     */
    private Location arrivalIn(World world, FarmWorldView farm, FarmWorldSettings now) {
        Scatter scatter = now.scatter().within(farm.border().orElse(null));
        if (!scatter.isOn()) {
            return world.getSpawnLocation();
        }
        Scatter.Point point = scatter.pick(random);
        int fromTheTop = Math.max(64, world.getMaxHeight() - FROM_THE_SKY);
        // Half a block in, so the player stands in the middle of the block rather than on its corner —
        // which on a one-block ledge is the difference between standing and falling.
        return new Location(world, point.x() + 0.5, fromTheTop, point.z() + 0.5);
    }

    /**
     * A sound for this player, when there is anything to play it with.
     *
     * <p>Guarded rather than assumed: a host without Core's effects must still be able to send somebody into a
     * farm world, and a missing sound is not a reason to refuse a teleport.
     */
    private void play(Player traveller, String cue) {
        if (effects != null && traveller != null) {
            effects.play(traveller.getUniqueId(), cue);
        }
    }

    /** What a refusal sounds like. The one cue players hear most, so it is the one worth being consistent. */
    private void refused(Player traveller) {
        play(traveller, Cues.NO);
    }

    private String waitLeft(UUID traveller) {
        return between.remaining(traveller).map(Times::describe).orElse("a moment");
    }

    /**
     * What the player is told at each point of the journey.
     *
     * <p>The wording is here rather than in Core because "Off to the farm world in 3…" is this server's
     * sentence, and a library that wrote it would be a library deciding what the server sounds like.
     */
    private final class Wording implements TravelWatcher {

        private final FarmWorldView farm;

        private Wording(FarmWorldView farm) {
            this.farm = farm;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "farmworlds.warming-up",
                    "name", farm.name(), "seconds", secondsLeft);
            // One tick per second of the wait, and the done cue on the last one — so somebody standing still
            // knows they are nearly there without watching chat. Both Core's, by meaning.
            play(traveller, secondsLeft <= 1 ? Cues.COUNTDOWN_DONE : Cues.COUNTDOWN);
        }

        /**
         * Charged here, and nowhere else.
         *
         * <p>The wait starts when somebody has actually arrived, so an interrupted warm-up, a farm world
         * whose world went away and a scatter that found nowhere safe all cost nothing — and there is no
         * refund anywhere, which matters because a refund is a blunt clear of the wait and would take
         * whatever else was on it with it.
         *
         * <p>They are also told how far out they came up. Not decoration: it is the one thing that makes
         * a scattered arrival legible rather than disorienting, and it is what somebody types into a
         * message to a friend who wants to meet them.
         */
        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            between.start(traveller.getUniqueId());
            play(traveller, Cues.TELEPORT);
            messages.send(traveller, "farmworlds.arrived",
                    "name", farm.name(),
                    "where", where.getBlockX() + ", " + where.getBlockZ());
            farm.untilRegenerated().ifPresent(left ->
                    // Said on arrival rather than only in the warnings: somebody who walks in twenty
                    // minutes before it goes has had no warning at all, and this is the moment they can
                    // still decide to do something else.
                    messages.send(traveller, "farmworlds.arrived-shortly-before",
                            "name", farm.name(), "time", Times.describe(left)));
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", farm.name());
            // A cancelled warm-up is a refusal to the player, whatever it is called in Core: they stood still,
            // something happened, and they are not going. The sound has to say that.
            play(traveller, Cues.NO);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", farm.name());
            play(traveller, Cues.NO);
        }
    }

    /**
     * This server's wording for each of Core's reasons.
     *
     * <p>A switch with no default, so that a reason added to {@link TravelReason} is a compiler error here
     * rather than a line of English nobody notices has gone untranslated.
     */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "farmworlds.cancelled.moved";
            case HURT -> "farmworlds.cancelled.hurt";
            case ALREADY_TRAVELLING -> "farmworlds.already-travelling";
            case WORLD_MISSING -> "farmworlds.not-loaded";
            case NOWHERE_SAFE -> "farmworlds.nowhere-safe";
            case COULD_NOT_CHECK -> "farmworlds.could-not-check";
            case TELEPORT_REFUSED -> "farmworlds.teleport-refused";
            case CANNOT_SCHEDULE -> "farmworlds.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "sending somebody into a farm world";
    }
}
