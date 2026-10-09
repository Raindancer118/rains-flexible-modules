package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import net.kyori.adventure.text.Component;
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

    private final TpaFees fees = new TpaFees(null);
    private final ChatButtons buttons;

    private volatile TpaSettings settings;

    public BackService(Travel travel, Messages messages, TpaSettings settings) {
        this(travel, messages, settings, (LongSupplier) null);
    }

    /**
     * @param buttons draws the offer to pay for skipping the wait; null offers it as plain text
     */
    public BackService(Travel travel, Messages messages, TpaSettings settings, ChatButtons buttons) {
        this(travel, messages, settings, null, buttons);
    }

    /**
     * The same, with the clock the wait is measured against handed in.
     *
     * @param clock milliseconds, and only ever read — null takes the system clock, which is what a
     *              running server wants. A test hands in one it can move itself
     */
    public BackService(Travel travel, Messages messages, TpaSettings settings, LongSupplier clock) {
        this(travel, messages, settings, clock, null);
    }

    private BackService(Travel travel, Messages messages, TpaSettings settings, LongSupplier clock,
                        ChatButtons buttons) {
        this.buttons = buttons;
        this.waits = clock == null ? new Cooldowns<>() : new Cooldowns<>(clock);
        this.travel = travel;
        this.returns = travel.cameFrom();
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh;
        fees.settings(fresh);
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
        return go(who, false);
    }

    /** What going back costs this player as written, for a button's lore. */
    public Money priceFor(Player who) {
        return who.hasPermission(PermissionNodes.BYPASS_FEE) ? Money.ZERO : fees.back();
    }

    /** The same, as the player would be charged and read: empty when it is free. */
    public String priceText(Player who) {
        Money quoted = Fees.quote(TpaFees.BACK, priceFor(who));
        return quoted.isPositive() ? Fees.format(quoted) : "";
    }

    /**
     * @param paySkip whether they have agreed to pay {@code tpa.skip-cooldown-price} if the wait is
     *                still running — only the offer's button sets it
     */
    private boolean go(Player who, boolean paySkip) {
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
        Money skipPrice = who.hasPermission(PermissionNodes.BYPASS_FEE) ? Money.ZERO : fees.skip();
        boolean ready = isReady(who);
        if (!ready && !(paySkip && skipPrice.isPositive())) {
            if (skipPrice.isPositive()) {
                offerToSkip(who, skipPrice);
            } else {
                messages.send(who, "tpa.back-too-soon", "time", waitLeft(who.getUniqueId()));
            }
            return false;
        }

        TpaFees.Charge charge = fees.charge(who.getUniqueId(), priceFor(who), TpaFees.BACK,
                ready ? Money.ZERO : skipPrice);
        if (!charge.paid()) {
            String key = charge.refusal().outcome() == EconomyResult.Outcome.NOT_ENOUGH
                    ? "tpa.cannot-afford" : "tpa.payment-failed";
            messages.send(who, key, "price", Fees.format(charge.refusal().amount()));
            return false;
        }

        // Taken only once everything has passed, so a refusal does not cost somebody the way back.
        returns.take(who.getUniqueId());
        fees.hold(who.getUniqueId(), charge.taken());

        int warmup = bypasses(who, PermissionNodes.BYPASS_WARMUP) ? 0 : settings.warmup();
        travel.go(who, destination,
                Trip.to(where.cause().describe())
                        .after(warmup)
                        .bringing(Companions.WHAT_YOU_LEAD),
                new Arriving(where));
        return true;
    }

    /** The refusal for somebody on a wait they could buy their way out of: the price, and a button. */
    private void offerToSkip(Player who, Money skipPrice) {
        String price = Fees.format(Fees.quote(TpaFees.SKIP, skipPrice));
        Component line = messages.prefixed("tpa.back-too-soon-skippable",
                "time", waitLeft(who.getUniqueId()), "price", price);
        if (buttons == null) {
            who.sendMessage(line);
            return;
        }
        Component button = buttons.label("<green>[Skip the wait]")
                .tooltip("<gray>Pay <white>" + price + "</white> and go back now")
                .forOnly(who.getUniqueId()).expiringIn(java.time.Duration.ofSeconds(30))
                .does(clicker -> {
                    Player clicked = org.bukkit.Bukkit.getPlayer(clicker);
                    if (clicked != null && clicked.isOnline()) {
                        go(clicked, true);
                    }
                }).render();
        who.sendMessage(line.appendSpace().append(button));
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
        // Standing through a wait when they quit: no trip, so no charge. The place they were going
        // back to is not restored either — they left, and Core drops their waypoints with them.
        fees.refundHeld(who);
    }

    /** Gives back everything paid for trips that never ended — for the module stopping. */
    public void refundPending() {
        fees.refundAllHeld();
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
            TpaFees.Taken spent = fees.settle(traveller.getUniqueId());
            if (spent != null && spent.total().isPositive()) {
                messages.send(traveller, "tpa.paid", "price", Fees.format(spent.total()));
            }
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
            giveBack(traveller);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            returns.remember(traveller.getUniqueId(), where);
            messages.send(traveller, keyFor(why), "what", where.cause().describe());
            giveBack(traveller);
        }

        private void giveBack(Player traveller) {
            TpaFees.Taken spent = fees.settle(traveller.getUniqueId());
            if (spent != null && spent.total().isPositive()) {
                fees.refund(traveller.getUniqueId(), spent);
                messages.send(traveller, "tpa.refunded", "price", Fees.format(spent.total()));
            }
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
