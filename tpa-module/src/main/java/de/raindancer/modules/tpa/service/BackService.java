package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.core.world.teleport.Returns;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.core.world.teleport.Waypoint;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Going back to where you were, or where you died.
 *
 * <h2>Where the waypoints come from now</h2>
 * {@link Returns} is Core's, and {@code Travel} records one on every arrival it performs. Which fixes
 * something the old plugin could not: {@code /back} lived here, so only <em>this</em> plugin's teleports
 * were remembered — going home and then typing {@code /back} took somebody to wherever their last
 * teleport request had been from. Now a warp, a home and a request are all just arrivals, and all three
 * are undoable.
 *
 * <p>A death still outranks a teleport until it is used, which is {@code Returns}' own rule: somebody
 * who died and was then moved wants {@code /back} to mean their body, because that is where their
 * things are.
 */
public final class BackService implements ITpaService {

    private final Returns returns;
    private final Travel travel;
    private final Messages messages;

    /** The wait between one player's returns, kept apart from the wait between requests. */
    private final Cooldowns<UUID> waits;

    private volatile TpaSettings settings;

    public BackService(Travel travel, Messages messages, TpaSettings settings) {
        this(travel, messages, settings, null);
    }

    /**
     * The same, with the clock the wait is measured against handed in.
     *
     * @param clock milliseconds, and only ever read — null takes the system clock, which is what a
     *              running server wants. A test hands in one it can move itself
     */
    public BackService(Travel travel, Messages messages, TpaSettings settings, LongSupplier clock) {
        this.waits = clock == null ? new Cooldowns<>() : new Cooldowns<>(clock);
        this.travel = travel;
        this.returns = travel.cameFrom();
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh;
        waits.every(Duration.ofSeconds(fresh.backCooldown()));
    }

    /** Whether the server has this at all. */
    public boolean isEnabled() {
        return settings.backEnabled();
    }

    /** Where they would go, without using it up — what a menu asks to grey a button. */
    public Optional<Waypoint> waiting(Player who) {
        return who == null ? Optional.empty() : returns.of(who.getUniqueId());
    }

    /**
     * Remembers where somebody died.
     *
     * <p>Only when the server has both {@code /back} and dying-counts switched on, and only for
     * somebody who may use it — recording it for a player who cannot is an entry that nothing will
     * ever read.
     */
    public void died(Player who, Location where) {
        if (!settings.backEnabled() || !settings.backOnDeath()
                || !who.hasPermission(PermissionNodes.BACK)) {
            return;
        }
        returns.remember(who.getUniqueId(),
                Waypoint.of(where, Waypoint.Cause.DEATH, System.currentTimeMillis()));
    }

    /**
     * Sends them back, or says why not.
     *
     * <p>Taken rather than read: {@code /back} twice in a row would otherwise be a way to hop between
     * two places for ever, which is a teleport with no cost at all.
     */
    public boolean go(Player who) {
        if (!settings.backEnabled()) {
            messages.send(who, "tpa.back-switched-off");
            return false;
        }
        Waypoint where = returns.of(who.getUniqueId()).orElse(null);
        if (where == null) {
            messages.send(who, "tpa.nowhere-to-go-back-to");
            return false;
        }
        Location destination = where.location().orElse(null);
        if (destination == null) {
            // Not a fault: a multiverse server unloads worlds for maintenance, and it works again when
            // the world comes back. So the waypoint is deliberately not taken.
            messages.send(who, "tpa.back-world-gone");
            return false;
        }
        if (!settings.allowCrossWorld() && !destination.getWorld().equals(who.getWorld())) {
            messages.send(who, "tpa.back-cross-world-off");
            return false;
        }
        if (!isReady(who)) {
            messages.send(who, "tpa.back-too-soon", "time", waitLeft(who.getUniqueId()));
            return false;
        }

        // Taken only once everything has passed, so a refusal does not cost somebody the way back.
        returns.take(who.getUniqueId());

        int warmup = bypasses(who, PermissionNodes.BYPASS_WARMUP) ? 0 : settings.warmup();
        travel.go(who, destination,
                Trip.to(where.cause().describe())
                        .after(warmup)
                        .bringing(Companions.WHAT_YOU_LEAD),
                new Arriving(where));
        return true;
    }

    /** Whether they may go back yet. */
    public boolean isReady(Player who) {
        return bypasses(who, PermissionNodes.BYPASS_COOLDOWN) || waits.isReady(who.getUniqueId());
    }

    private boolean bypasses(Player who, String node) {
        return who.hasPermission(node) || (settings.operatorsBypass() && who.isOp());
    }

    private String waitLeft(UUID who) {
        return waits.remaining(who).map(Durations::describe).orElse("a moment");
    }

    /**
     * Lets go of a player who has left, without letting go of what they still owe.
     *
     * <p>Called on quit. It used to drop this player's entry outright, which made reconnecting a
     * way straight past the wait: go, log out, log back in, go again. The entry only exists to say
     * "not yet", so throwing it away is the same thing as saying yes.
     *
     * <p>What the quit handler was for is keeping the map from growing by an entry per player
     * forever, and {@link Cooldowns#sweep()} does that without touching anybody: it drops every
     * wait already over — this player's included, when it is — and leaves the running ones alone.
     *
     * @param who whose quit prompted this. Deliberately not singled out: the sweep is what bounds
     *            the map, and one player leaving is only when it is worth doing
     */
    public void leaves(UUID who) {
        waits.sweep();
    }

    /** The wait itself, for the tests in this package. */
    Cooldowns<UUID> waits() {
        return waits;
    }

    /** What the traveller is told on the way. */
    private final class Arriving implements TravelWatcher {

        private final Waypoint where;

        private Arriving(Waypoint where) {
            this.where = where;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "tpa.back-warming-up",
                    "what", where.cause().describe(), "seconds", secondsLeft);
        }

        /**
         * Charged here, and nowhere else.
         *
         * <p>The wait starts when they have actually arrived, so being knocked out of the countdown
         * costs nothing — and there is no refund to write, which matters because a refund is a blunt
         * clear that would take whatever else was on the wait.
         */
        @Override
        public void arrived(Player traveller, Location destination, Trip trip) {
            waits.start(traveller.getUniqueId());
            messages.send(traveller, "tpa.back-arrived", "what", where.cause().describe());
        }

        /**
         * Given back when the journey does not happen.
         *
         * <p>They typed {@code /back} and did not get there, so the place they were going to has to
         * still be waiting for them — otherwise being hit by a zombie costs somebody the way back to
         * their own body.
         */
        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            returns.remember(traveller.getUniqueId(), where);
            messages.send(traveller, keyFor(why), "what", where.cause().describe());
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            returns.remember(traveller.getUniqueId(), where);
            messages.send(traveller, keyFor(why), "what", where.cause().describe());
        }
    }

    /** This server's wording for each of Core's reasons. A switch with no default, on purpose. */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "tpa.back-cancelled.moved";
            case HURT -> "tpa.back-cancelled.hurt";
            case ALREADY_TRAVELLING -> "tpa.already-travelling";
            case WORLD_MISSING -> "tpa.back-world-gone";
            case NOWHERE_SAFE -> "tpa.back-nowhere-safe";
            case COULD_NOT_CHECK -> "tpa.could-not-check";
            case TELEPORT_REFUSED -> "tpa.teleport-refused";
            case CANNOT_SCHEDULE -> "tpa.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "going back to where you were, or where you died";
    }
}
