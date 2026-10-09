package de.raindancer.modules.warp.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.store.WarpRegistry;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.store.WarpCatalogue;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;

/**
 * Sending somebody to a warp.
 *
 * <h2>What is here and what is Core's</h2>
 * The waiting, the movement cancelling, finding somewhere safe to land and the teleport itself are
 * all {@link Travel}'s — the same code the teleport requests and the homes use, which is the point
 * of it being there. The cooldown is {@link WarpRegistry}'s. What is left here, and it is the whole job of
 * this class, is the order the questions are asked in and what each answer is worded as.
 *
 * <h2>The order, and why it is that order</h2>
 * Permission, then the world, then the cooldown, then the warm-up. The cooldown is asked
 * <em>last</em> of the three refusals on purpose: asking it first means a typo, a warp whose world
 * is unloaded, or a warp somebody may not use each cost fifteen seconds of waiting for a warp that
 * never happened.
 */
public final class TravelService implements IWarpService {

    private final WarpCatalogue catalogue;
    private final WarpRegistry warps;
    private final Travel travel;
    private final WarpAccessRule access;
    private final Messages messages;

    /** Visit fees; null for a host that charges none. */
    private final WarpVisitFees visits;
    /** Rent, which is what closes a warp; null for a host that charges none. */
    private final WarpRentService rent;

    private volatile WarpSettings settings;

    public TravelService(WarpCatalogue catalogue, WarpRegistry warps, Travel travel,
                         WarpAccessRule access, Messages messages, WarpSettings settings) {
        this(catalogue, warps, travel, access, messages, settings, null, null);
    }

    public TravelService(WarpCatalogue catalogue, WarpRegistry warps, Travel travel,
                         WarpAccessRule access, Messages messages, WarpSettings settings,
                         WarpVisitFees visits, WarpRentService rent) {
        this.visits = visits;
        this.rent = rent;
        this.catalogue = catalogue;
        this.warps = warps;
        this.travel = travel;
        this.access = access;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(WarpSettings fresh) {
        this.settings = fresh;
        // The cooldown lives on this module's WarpRegistry, so a changed setting has to be pushed into it. Left
        // out, the file says thirty seconds and the server keeps enforcing yesterday's fifteen.
        warps.cooldown(Duration.ofSeconds(fresh.cooldown()));
    }

    /**
     * Sends this player to the warp of this name, or tells them why not.
     *
     * <p>Every path out of here says something. A command that silently does nothing is one people
     * type four more times, and then report as broken.
     */
    public void goTo(Player traveller, String name) {
        if (traveller == null) {
            return;
        }
        Warp warp = catalogue.byName(name).orElse(null);
        if (warp == null || !mayUse(traveller, warp)) {
            // The same answer for "no such warp" and "not yours", deliberately. Telling somebody a
            // warp exists but is not for them is telling them the staff warps are called 'staff'.
            messages.send(traveller, "warps.unknown", "name", String.valueOf(name));
            return;
        }
        go(traveller, warp);
    }

    /** The same, when the warp is already in hand — what a menu click has. */
    public void go(Player traveller, Warp warp) {
        if (traveller == null || warp == null) {
            return;
        }
        if (!mayUse(traveller, warp)) {
            messages.send(traveller, "warps.unknown", "name", warp.name());
            return;
        }

        // A warp closed for unpaid rent is shut to everybody but its owner and staff. Asked after
        // the permission, so somebody who may not use the warp is still told there is none.
        if (rent != null && rent.isClosed(warp) && !access.mayChange(traveller::hasPermission,
                traveller.getUniqueId(), warp.owner().orElse(null))) {
            messages.send(traveller, "warps.closed", "name", warp.label());
            return;
        }

        // Asked, not spent. The wait is started when they actually arrive; see the watcher below.
        if (!warps.isReadyToWarp(traveller.getUniqueId())) {
            messages.send(traveller, "warps.on-cooldown", "time", waitLeft(traveller.getUniqueId()));
            return;
        }
        boolean paying = visits != null;
        if (paying && travel.isTravelling(traveller.getUniqueId())) {
            // Before charging: Core refuses a second trip, and that refusal must not be what hands
            // back the money of the first.
            messages.send(traveller, "warps.already-travelling");
            return;
        }
        if (paying && !visits.charge(traveller, warp)) {
            return;
        }
        depart(traveller, warp.label(), warp.poi());
    }

    /** Lets go of a player who has left, paying back a visit that never finished. */
    public void leaves(UUID who) {
        if (visits != null) {
            visits.refund(who);
        }
    }

    /**
     * Sends somebody to a claim's warp — the same journey, wait and cooldown as a warp. Whether the claim
     * lets them in is the caller's to have asked; the claim asks again on arrival.
     */
    public void goToPlace(Player traveller, String label, de.raindancer.core.world.poi.Poi place) {
        if (traveller == null || place == null) {
            return;
        }
        if (!warps.isReadyToWarp(traveller.getUniqueId())) {
            messages.send(traveller, "warps.on-cooldown", "time", waitLeft(traveller.getUniqueId()));
            return;
        }
        depart(traveller, label, place);
    }

    /** Whether this traveller may use it — their own warp, one they were let into, or one their permissions open. */
    private boolean mayUse(Player traveller, Warp warp) {
        return access.mayUse(catalogue.accessOf(warp), traveller::hasPermission, traveller.getUniqueId(),
                warp.owner().orElse(null), warp.members());
    }

    private void depart(Player traveller, String label, de.raindancer.core.world.poi.Poi place) {
        Location target = place.location().orElse(null);
        if (target == null) {
            // Not a fault: a multiverse server unloads worlds for maintenance and the warp works
            // again when the world comes back. Nothing has been charged, so there is nothing to
            // give back.
            messages.send(traveller, "warps.world-missing", "name", label);
            return;
        }
        WarpSettings now = settings;
        Trip trip = Trip.to(label)
                .after(now.warmup())
                .searching(now.arrivalRadius())
                // What the player is holding on to. Gathered and moved by Core, which is what the
                // homes and the teleport requests will use for the same thing.
                .bringing(now.companions());
        if (!now.safeArrival()) {
            trip = trip.exactly();
        }
        travel.go(traveller, target, trip, new Wording(label));
    }

    private String waitLeft(UUID traveller) {
        return warps.remaining(traveller).map(Durations::describe).orElse("a moment");
    }

    /**
     * What the player is told at each point of the journey.
     *
     * <p>The wording is here rather than in Core because "Warping to the mine in 3…" is this
     * server's sentence, and a library that wrote it would be a library deciding what the server
     * sounds like.
     */
    private final class Wording implements TravelWatcher {

        private final String label;

        private Wording(String label) {
            this.label = label;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "warps.warming-up",
                    "name", label, "seconds", secondsLeft);
        }

        /**
         * Charged here, and nowhere else.
         *
         * <p>The wait starts when somebody has actually arrived. So being knocked out of a warm-up by
         * a zombie costs them nothing, a warp into an unloaded world costs them nothing, and there is
         * no refund anywhere — which matters, because a refund is a blunt clear of the wait and would
         * take whatever else was on it with it.
         */
        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            if (visits != null) {
                visits.settle(traveller.getUniqueId());
            }
            warps.recordUse(traveller.getUniqueId());
            messages.send(traveller, "warps.arrived", "name", label);
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", label);
            paidBack(traveller);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", label);
            paidBack(traveller);
        }

        private void paidBack(Player traveller) {
            if (visits == null) {
                return;
            }
            Money back = visits.refund(traveller.getUniqueId());
            if (back.isPositive()) {
                messages.send(traveller, "warps.visit.refunded", "price", Fees.format(back));
            }
        }
    }

    /**
     * This server's wording for each of Core's reasons.
     *
     * <p>A switch with no default, so that a reason added to {@link TravelReason} is a compiler
     * error here rather than a line of English nobody notices has gone untranslated.
     */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "warps.cancelled.moved";
            case HURT -> "warps.cancelled.hurt";
            case ALREADY_TRAVELLING -> "warps.already-travelling";
            case WORLD_MISSING -> "warps.world-missing";
            case NOWHERE_SAFE -> "warps.nowhere-safe";
            case COULD_NOT_CHECK -> "warps.could-not-check";
            case TELEPORT_REFUSED -> "warps.teleport-refused";
            case CANNOT_SCHEDULE -> "warps.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "sending somebody to a warp";
    }
}
