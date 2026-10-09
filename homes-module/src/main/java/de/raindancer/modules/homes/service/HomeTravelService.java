package de.raindancer.modules.homes.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.homes.HomeSettings;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Sending somebody home.
 *
 * <h2>What is here and what is Core's</h2>
 * The standing still, the cancelling when somebody moves or is hurt, finding somewhere safe to land
 * and the teleport itself are all {@link Travel}'s — which is the same code the warps and the teleport
 * requests use. That is not a tidiness argument: this class is where {@code Travel} came from. The old
 * plugin had its own copy of all of it, identical to the teleport requests' copy down to the helper
 * that decides whether somebody has moved, and the two were fixed separately for years.
 *
 * <p>The cooldown is Core's {@link Cooldowns}. What is left, and it is the whole job of this class, is
 * the order the refusals are asked in and what each one is worded as.
 *
 * <h2>The order, and why</h2>
 * World loaded, then cross-world, then the cooldown. The cooldown is asked <em>last</em> on purpose:
 * asking it first means a home in an unloaded world, or one in the wrong world on a server that
 * forbids that, costs somebody a wait for a teleport that never happened.
 */
public final class HomeTravelService implements IHomeService {

    private final Travel travel;
    private final Messages messages;
    private final Effects effects;

    /**
     * The wait between teleports.
     *
     * <p>Charged on arrival rather than up front — see {@link Arriving}. Core's, because the
     * check-then-record version this plugin had could let two clicks in one millisecond both through.
     */
    private final Cooldowns<UUID> waits;

    /** The economy source of what going home costs. */
    public static final String SOURCE = "homes.teleport";

    /**
     * What each player has paid for a trip that has not finished yet.
     *
     * <p>Charged when the trip starts, because Core's {@code Travel} has no hook between the wait and
     * the teleport; it is handed back from here when the trip is cancelled, refused or cut off by a
     * logout. Removed under the lock, so however many of those arrive the money goes back once.
     */
    private final Map<UUID, Money> paid = new HashMap<>();

    private volatile HomeSettings settings;

    public HomeTravelService(Travel travel, Messages messages, Effects effects, HomeSettings settings) {
        this(travel, messages, effects, settings, null);
    }

    /**
     * The same, with the clock the wait is measured against handed in.
     *
     * @param clock milliseconds, and only ever read — null takes the system clock, which is what a
     *              running server wants. A test hands in one it can move itself
     */
    public HomeTravelService(Travel travel, Messages messages, Effects effects, HomeSettings settings,
                             LongSupplier clock) {
        this.waits = clock == null ? new Cooldowns<>() : new Cooldowns<>(clock);
        this.travel = travel;
        this.messages = messages;
        this.effects = effects;
        settings(settings);
    }

    @Override
    public void settings(HomeSettings fresh) {
        this.settings = fresh;
        waits.every(Duration.ofSeconds(fresh.cooldown()));
    }

    /** Whether this player could go home right now — what a menu asks to grey a button. */
    public boolean isReady(Player traveller) {
        return traveller != null
                && (bypasses(traveller, PermissionNodes.BYPASS_COOLDOWN)
                        || waits.isReady(traveller.getUniqueId()));
    }

    /**
     * Sends them there, or tells them why not.
     *
     * <p>Every path out says something. A command that silently does nothing is one people type four
     * more times, and then report as broken.
     */
    public void go(Player traveller, Home home) {
        if (traveller == null || home == null) {
            return;
        }
        HomeSettings now = settings;

        Location destination = home.poi().location().orElse(null);
        if (destination == null) {
            // Not a fault and not a reason to delete it: a multiverse server unloads worlds for
            // maintenance and the home works again when the world comes back.
            messages.send(traveller, "homes.world-not-loaded",
                    "name", home.name(), "world", home.world());
            return;
        }
        if (!now.allowCrossWorld() && !home.isIn(traveller.getWorld().getName())) {
            messages.send(traveller, "homes.cross-world-off", "name", home.name());
            return;
        }
        if (!isReady(traveller)) {
            messages.send(traveller, "homes.on-cooldown",
                    "time", waitLeft(traveller.getUniqueId()));
            return;
        }

        if (!charge(traveller, home, now)) {
            return;
        }

        int warmup = bypasses(traveller, PermissionNodes.BYPASS_WARMUP) ? 0 : now.warmup();
        Trip trip = Trip.to(home.name())
                .after(warmup)
                .bringing(now.bringWhatYouLead()
                        ? Companions.WHAT_YOU_LEAD
                        : Companions.NOBODY);
        if (!now.safeArrival()) {
            trip = trip.exactly();
        }
        if (!now.playSound()) {
            trip = trip.quiet();
        }
        travel.go(traveller, destination, trip, new Arriving(home));
    }

    /** Takes the price of the trip. @return whether the trip may go on; a refusal has been said */
    private boolean charge(Player traveller, Home home, HomeSettings now) {
        Money written = Fees.amount(now.teleportPrice());
        if (!written.isPositive() || bypasses(traveller, PermissionNodes.BYPASS_FEE)) {
            return true;
        }
        UUID who = traveller.getUniqueId();
        if (travel.isTravelling(who)) {
            // Asked before charging: Core refuses a second trip, and that refusal must not be what
            // hands back the money of the first.
            messages.send(traveller, "homes.already-travelling");
            return false;
        }
        EconomyResult result = Fees.charge(who, written, "Trip to home " + home.name(), SOURCE);
        if (!result.succeeded()) {
            switch (result.outcome()) {
                case NOT_ENOUGH -> messages.send(traveller, "homes.teleport.cannot-afford",
                        "price", Fees.format(result.amount()));
                case UNAVAILABLE -> messages.send(traveller, "homes.teleport.no-economy");
                default -> messages.send(traveller, "homes.teleport.not-paid");
            }
            return false;
        }
        if (result.amount().isPositive()) {
            synchronized (paid) {
                paid.put(who, result.amount());
            }
        }
        return true;
    }

    /** Hands back what this player paid for a trip that did not happen. @return what, or zero */
    private Money refund(UUID who) {
        Money taken;
        synchronized (paid) {
            taken = paid.remove(who);
        }
        if (taken == null) {
            return Money.ZERO;
        }
        Fees.refund(who, taken, "Trip home did not happen", SOURCE);
        return taken;
    }

    private void settle(UUID who) {
        synchronized (paid) {
            paid.remove(who);
        }
    }

    /** Hands back everything still waiting on a trip, for a module being stopped. */
    public void refundAll() {
        java.util.List<UUID> owing;
        synchronized (paid) {
            owing = java.util.List.copyOf(paid.keySet());
        }
        owing.forEach(this::refund);
    }

    /**
     * Whether this player skips something.
     *
     * <p>The node, or being an operator on a server whose owner said operators bypass. That setting
     * defaults to off, deliberately: an admin who silently bypasses a feature is the one person who
     * cannot test it.
     */
    private boolean bypasses(Player who, String node) {
        return who.hasPermission(node) || (settings.operatorsBypass() && who.isOp());
    }

    private String waitLeft(UUID traveller) {
        return waits.remaining(traveller).map(Durations::describe).orElse("a moment");
    }

    /**
     * What the player is told on the way.
     *
     * <p>The wording is here rather than in Core because "Welcome home" is this server's sentence, and
     * a library that wrote it would be a library deciding what the server sounds like.
     */
    private final class Arriving implements TravelWatcher {

        private final Home home;

        private Arriving(Home home) {
            this.home = home;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "homes.warming-up",
                    "name", home.name(), "seconds", secondsLeft);
        }

        /**
         * Charged here, and nowhere else.
         *
         * <p>The wait starts when somebody has actually arrived, so being knocked out of it by a
         * zombie costs them nothing and there is no refund anywhere. A refund would be a blunt clear
         * of the wait, taking whatever else was on it.
         */
        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            settle(traveller.getUniqueId());
            waits.start(traveller.getUniqueId());
            messages.send(traveller, "homes.arrived", "name", home.name());
            // The arrival sound is Core's now (Travel plays it, in the traveller's own choice if they
            // made one); the setting below only decides whether a home trip is quiet.
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", home.name());
            told(traveller, refund(traveller.getUniqueId()));
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", home.name());
            told(traveller, refund(traveller.getUniqueId()));
        }

        private void told(Player traveller, Money back) {
            if (back.isPositive()) {
                messages.send(traveller, "homes.teleport.refunded", "price", Fees.format(back));
            }
        }
    }

    /**
     * This server's wording for each of Core's reasons.
     *
     * <p>A switch with no default, so a reason added to {@link TravelReason} is a compiler error here
     * rather than a line of English nobody notices has gone untranslated.
     */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "homes.cancelled.moved";
            case HURT -> "homes.cancelled.hurt";
            case ALREADY_TRAVELLING -> "homes.already-travelling";
            case WORLD_MISSING -> "homes.world-gone";
            case NOWHERE_SAFE -> "homes.nowhere-safe";
            case COULD_NOT_CHECK -> "homes.could-not-check";
            case TELEPORT_REFUSED -> "homes.teleport-refused";
            case CANNOT_SCHEDULE -> "homes.cannot-schedule";
        };
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
        refund(who);
        waits.sweep();
    }

    @Override
    public String describe() {
        return "sending somebody home";
    }
}
