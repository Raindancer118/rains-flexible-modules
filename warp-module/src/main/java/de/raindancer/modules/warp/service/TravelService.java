package de.raindancer.modules.warp.service;

import de.raindancer.core.moderation.punishment.Durations;
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

    private volatile WarpSettings settings;

    public TravelService(WarpCatalogue catalogue, WarpRegistry warps, Travel travel,
                         WarpAccessRule access, Messages messages, WarpSettings settings) {
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
        if (warp == null || !access.mayUse(catalogue.accessOf(warp), traveller::hasPermission)) {
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
        if (!access.mayUse(catalogue.accessOf(warp), traveller::hasPermission)) {
            messages.send(traveller, "warps.unknown", "name", warp.name());
            return;
        }

        // Asked, not spent. Charging up front would mean giving it back whenever a warm-up is
        // interrupted, and giving it back means clearing the wait — which wipes whatever else was on
        // it. So the wait is started when they actually arrive; see the watcher below.
        if (!warps.isReadyToWarp(traveller.getUniqueId())) {
            messages.send(traveller, "warps.on-cooldown", "time", waitLeft(traveller.getUniqueId()));
            return;
        }
        depart(traveller, warp);
    }

    private void depart(Player traveller, Warp warp) {
        Location target = warp.poi().location().orElse(null);
        if (target == null) {
            // Not a fault: a multiverse server unloads worlds for maintenance and the warp works
            // again when the world comes back. Nothing has been charged, so there is nothing to
            // give back.
            messages.send(traveller, "warps.world-missing", "name", warp.label());
            return;
        }
        WarpSettings now = settings;
        Trip trip = Trip.to(warp.label())
                .after(now.warmup())
                .searching(now.arrivalRadius())
                // What the player is holding on to. Gathered and moved by Core, which is what the
                // homes and the teleport requests will use for the same thing.
                .bringing(now.companions());
        if (!now.safeArrival()) {
            trip = trip.exactly();
        }
        travel.go(traveller, target, trip, new Wording(warp));
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

        private final Warp warp;

        private Wording(Warp warp) {
            this.warp = warp;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "warps.warming-up",
                    "name", warp.label(), "seconds", secondsLeft);
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
            warps.recordUse(traveller.getUniqueId());
            messages.send(traveller, "warps.arrived", "name", warp.label());
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", warp.label());
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", warp.label());
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
