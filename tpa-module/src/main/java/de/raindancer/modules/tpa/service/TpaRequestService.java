package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.store.TpaRequests;
import de.raindancer.modules.tpa.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Asking, answering, and the journey that follows.
 *
 * <h2>What is here and what is Core's</h2>
 * The standing still, the cancelling on movement and damage, finding somewhere safe to land and the
 * teleport itself are {@link Travel}'s — and this plugin is one of the two that {@code Travel} was made
 * from. Its own copy was identical to the homes plugin's, down to the helper that decides whether
 * somebody has moved, and the two were fixed separately for years.
 *
 * <h2>The destination is read when they arrive, not when they accept</h2>
 * A {@code Supplier<Location>}, resolved at the last moment. Somebody who accepts a request and then
 * walks twenty blocks is still where the traveller should end up — reading it at accept time would put
 * them where that person was standing three seconds ago, which on a server with a warm-up is most of
 * the time.
 *
 * <h2>The sweep only reports</h2>
 * A request is expired the moment it is looked at, so {@link #sweep} exists to <em>tell</em> both sides
 * that one lapsed. The old plugin scheduled its sweep at exactly the expiry, the task landed
 * milliseconds early, and requests sat there unanswerable with nobody told.
 */
public final class TpaRequestService implements ITpaService {

    private final Plugin plugin;
    private final TpaRequests requests;
    private final TpaPrefsService prefs;
    private final TpaAskingRule asking;
    private final Travel travel;
    private final Messages messages;
    private final ChatButtons buttons;
    private final Vanish vanish;
    private final TpaFees fees = new TpaFees(null);

    /** The wait between one player's requests. Core's, so two clicks in a millisecond cannot both pass. */
    private final Cooldowns<UUID> waits;

    private volatile TpaSettings settings;

    public TpaRequestService(Plugin plugin, TpaRequests requests, TpaPrefsService prefs,
                             TpaAskingRule asking, Travel travel, Messages messages,
                             ChatButtons buttons, Vanish vanish, TpaSettings settings) {
        this(plugin, requests, prefs, asking, travel, messages, buttons, vanish, settings, null);
    }

    /**
     * The same, with the clock the wait between requests is measured against handed in.
     *
     * @param clock milliseconds, and only ever read — null takes the system clock, which is what a
     *              running server wants. A test hands in one it can move itself
     */
    public TpaRequestService(Plugin plugin, TpaRequests requests, TpaPrefsService prefs,
                             TpaAskingRule asking, Travel travel, Messages messages,
                             ChatButtons buttons, Vanish vanish, TpaSettings settings,
                             LongSupplier clock) {
        this.waits = clock == null ? new Cooldowns<>() : new Cooldowns<>(clock);
        this.plugin = plugin;
        this.requests = requests;
        this.prefs = prefs;
        this.asking = asking;
        this.travel = travel;
        this.messages = messages;
        this.buttons = buttons;
        this.vanish = vanish;
        settings(settings);
    }

    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh;
        fees.settings(fresh);
        waits.every(Duration.ofSeconds(fresh.cooldown()));
        requests.standingFor(Duration.ofSeconds(fresh.requestStanding()));
    }

    // ------------------------------------------------------------------------ asking

    /**
     * Asks somebody, or says why not.
     *
     * @return whether the request was made
     */
    public boolean ask(Player from, Player to, TpaKind kind) {
        return ask(from, to, kind, false);
    }

    /**
     * @param paySkip whether they have agreed to pay {@code tpa.skip-cooldown-price} if the wait between
     *                requests is still running — only the offer's button sets it
     */
    private boolean ask(Player from, Player to, TpaKind kind, boolean paySkip) {
        if (!vanish.canSee(from.getUniqueId(), to.getUniqueId())) {
            // Reported exactly as if nobody by that name were here at all — the same message the
            // command already sends for somebody who is not online — so asking is not a second way
            // to find out a moderator is vanished.
            messages.send(from, "tpa.no-such-player", "player", to.getName());
            return false;
        }
        TpaSettings now = settings;
        boolean reachable = now.allowCrossWorld() || from.getWorld().equals(to.getWorld());
        boolean mayBypass = from.hasPermission(PermissionNodes.BYPASS_TOGGLE);

        boolean ready = isReadyToAsk(from);
        Money skipPrice = skipPriceFor(from);
        boolean buyingSkip = !ready && paySkip && skipPrice.isPositive();

        TpaAskingRule.Verdict verdict = asking.check(from.getUniqueId(), to.getUniqueId(),
                prefs.of(to.getUniqueId()), reachable, mayBypass,
                requests.has(from.getUniqueId(), to.getUniqueId()),
                !ready && !buyingSkip);
        if (!verdict.isFine()) {
            if (verdict == TpaAskingRule.Verdict.TOO_SOON && skipPrice.isPositive()) {
                offerToSkip(from, to, kind);
                return false;
            }
            messages.send(from, verdict.messageKey(),
                    "player", to.getName(),
                    "time", waitLeft(from.getUniqueId()));
            return false;
        }
        TpaFees.Taken bought = TpaFees.Taken.NOTHING;
        if (buyingSkip) {
            TpaFees.Charge charge = fees.charge(from.getUniqueId(), Money.ZERO, TpaFees.TRIP, skipPrice);
            if (!charge.paid()) {
                tellWhyNotPaid(from, charge.refusal());
                return false;
            }
            bought = charge.taken();
        }

        // One call, not two: put() after displacedBy() would find the request displacedBy() just
        // stored and report it back as "asking the same person again" — always, even the first time.
        TpaRequests.Outcome outcome = requests.ask(from.getUniqueId(), to.getUniqueId(), kind);

        // Whatever they had asked before is pushed aside — and the person who was waiting on it has to
        // be told, or they go on waiting to answer a request that no longer exists.
        outcome.displaced().ifPresent(displaced -> onlineOf(displaced.to()).ifPresent(bumped ->
                messages.send(bumped, "tpa.withdrawn-by-asker", "player", from.getName())));

        Optional<TpaRequest> made = outcome.request();
        if (made.isEmpty()) {
            // Only reachable if something changed between the rule and here — another thread asked
            // first. Saying so beats silence.
            messages.send(from, "tpa.already-asked", "player", to.getName());
            fees.refund(from.getUniqueId(), bought);
            return false;
        }

        waits.start(from.getUniqueId());
        prefs.seen(from);
        prefs.seen(to);

        messages.send(from, "tpa.asked",
                "player", to.getName(),
                "seconds", now.requestStanding());
        // To chat, never the action bar: this has to still be there when they come back to the
        // keyboard, and an action bar is gone in three seconds.
        Component asked = messages.prefixed(kind == TpaKind.TO ? "tpa.asked-you-to" : "tpa.asked-you-here",
                "player", from.getName(),
                "seconds", now.requestStanding());
        // [Accept] [Deny] under the words, so answering is one click rather than remembering a command.
        // Bound to this request alone: a click after somebody has asked again, or after this one has
        // lapsed, answers whatever request stands at that moment through accept()/deny() themselves —
        // both already read from the store fresh, exactly as bare /tpaccept does.
        Component question = buttons.ask(to.getUniqueId(), Duration.ofSeconds(now.requestStanding()),
                clicker -> onlineOf(clicker).ifPresent(answerer -> accept(answerer, from.getUniqueId())),
                clicker -> onlineOf(clicker).ifPresent(answerer -> deny(answerer, from.getUniqueId())));
        to.sendMessage(asked.appendNewline().append(question));
        tellThePrice(from, to, kind);

        sweepAfter(from, made.get());
        return true;
    }

    /**
     * Tells both sides when a request lapses.
     *
     * <p>Scheduled on the asker, with a few ticks in hand. The old plugin scheduled it at exactly the
     * expiry and the task landed milliseconds early — the sweep found nothing expired, the one-shot
     * task was spent, and the request sat there for ever with nobody told. Found by a pair of bots on a
     * live server, which is the only way it ever would have been.
     */
    private void sweepAfter(Player asker, TpaRequest made) {
        long ticks = Math.max(1, (made.expiresAt() - System.currentTimeMillis()) / 50L) + GRACE_TICKS;
        Scheduling.entityLater(plugin, asker, ticks, this::sweep);
    }

    /** How much late is enough to be sure a request has actually expired. */
    private static final long GRACE_TICKS = 4;

    /** Reports whatever has run out. Harmless if it fires early, twice, or never. */
    public void sweep() {
        for (TpaRequest lapsed : requests.expire()) {
            onlineOf(lapsed.from()).ifPresent(asker ->
                    messages.send(asker, "tpa.yours-ran-out",
                            "player", prefs.nameOf(lapsed.to())));
            onlineOf(lapsed.to()).ifPresent(asked ->
                    messages.send(asked, "tpa.theirs-ran-out",
                            "player", prefs.nameOf(lapsed.from())));
        }
    }

    // ------------------------------------------------------------------------ answering

    /**
     * Accepts a request.
     *
     * @param from who asked, or null for whichever is newest
     */
    public boolean accept(Player answering, UUID from) {
        Optional<TpaRequest> taken = requests.take(answering.getUniqueId(), from);
        if (taken.isEmpty()) {
            messages.send(answering, "tpa.nothing-to-answer");
            return false;
        }
        TpaRequest request = taken.get();

        Player traveller = onlineOf(request.traveller()).orElse(null);
        Player destination = onlineOf(request.destination()).orElse(null);
        if (traveller == null || destination == null) {
            messages.send(answering, "tpa.no-longer-online",
                    "player", prefs.nameOf(traveller == null ? request.traveller()
                            : request.destination()));
            return false;
        }

        if (travel.isTravelling(traveller.getUniqueId()) || fees.holding(traveller.getUniqueId())) {
            // One journey at a time — a second would be paid for while the first still holds its fee.
            if (answering.equals(traveller)) {
                messages.send(answering, "tpa.already-travelling");
            } else {
                messages.send(answering, "tpa.they-are-travelling", "player", traveller.getName());
            }
            return false;
        }
        TpaFees.Taken paid = chargeTraveller(answering, traveller, destination);
        if (paid == null) {
            return false;
        }

        messages.send(answering, "tpa.you-accepted", "player", prefs.nameOf(request.from()));
        onlineOf(request.from()).filter(asker -> !asker.equals(answering))
                .ifPresent(asker -> messages.send(asker, "tpa.they-accepted",
                        "player", answering.getName()));

        send(traveller, destination, paid);
        return true;
    }

    /** Refuses one. */
    public boolean deny(Player answering, UUID from) {
        Optional<TpaRequest> taken = requests.take(answering.getUniqueId(), from);
        if (taken.isEmpty()) {
            messages.send(answering, "tpa.nothing-to-answer");
            return false;
        }
        messages.send(answering, "tpa.you-refused", "player", prefs.nameOf(taken.get().from()));
        onlineOf(taken.get().from()).ifPresent(asker ->
                messages.send(asker, "tpa.they-refused", "player", answering.getName()));
        return true;
    }

    /**
     * Takes back your own request, or gives up on a journey already begun.
     *
     * <p>The journey first: somebody standing still with a countdown on screen who types the cancel
     * command means that countdown, not a request they may also have outstanding.
     */
    public boolean cancel(Player who) {
        if (travel.isTravelling(who.getUniqueId())) {
            travel.cancel(who, TravelReason.MOVED);
            messages.send(who, "tpa.called-it-off");
            return true;
        }
        Optional<TpaRequest> withdrawn = requests.withdraw(who.getUniqueId());
        if (withdrawn.isEmpty()) {
            messages.send(who, "tpa.nothing-to-take-back");
            return false;
        }
        messages.send(who, "tpa.took-it-back", "player", prefs.nameOf(withdrawn.get().to()));
        onlineOf(withdrawn.get().to()).ifPresent(asked ->
                messages.send(asked, "tpa.withdrawn-by-asker", "player", who.getName()));
        return true;
    }

    // ------------------------------------------------------------------------ going

    /**
     * Sends whoever travels to whoever they are going to.
     *
     * <p>The destination is a supplier: the person being travelled to may keep walking while the
     * countdown runs, and the traveller should end up where they actually are.
     */
    private void send(Player traveller, Player destination, TpaFees.Taken paid) {
        TpaSettings now = settings;
        int warmup = bypasses(traveller, PermissionNodes.BYPASS_WARMUP) ? 0 : now.warmup();

        Trip trip = Trip.to(destination.getName())
                .after(warmup)
                .bringing(Companions.WHAT_YOU_LEAD);
        Location whereTheyAre = destination.getLocation();
        if (!fees.hold(traveller.getUniqueId(), paid)) {
            fees.refund(traveller.getUniqueId(), paid);
            messages.send(traveller, "tpa.already-travelling");
            return;
        }
        travel.go(traveller, whereTheyAre, trip, new Arriving(destination.getName()));
    }

    // ------------------------------------------------------------------------ paying

    /** What a trip costs this player as written: nothing at all with the bypass. */
    private Money tripPriceFor(Player traveller, Player destination) {
        return traveller.hasPermission(PermissionNodes.BYPASS_FEE) ? Money.ZERO
                : fees.trip(traveller.getLocation(), destination.getLocation());
    }

    private Money skipPriceFor(Player asker) {
        return asker.hasPermission(PermissionNodes.BYPASS_FEE) ? Money.ZERO : fees.skip();
    }

    /**
     * Takes the trip's price from whoever travels.
     *
     * @return what was taken, or null after telling everybody concerned why nobody is going
     */
    private TpaFees.Taken chargeTraveller(Player answering, Player traveller, Player destination) {
        TpaFees.Charge charge = fees.charge(traveller.getUniqueId(),
                tripPriceFor(traveller, destination), TpaFees.TRIP, Money.ZERO);
        if (charge.paid()) {
            return charge.taken();
        }
        tellWhyNotPaid(traveller, charge.refusal());
        if (!traveller.equals(answering)) {
            messages.send(answering, "tpa.traveller-cannot-pay", "player", traveller.getName());
        }
        return null;
    }

    private void tellWhyNotPaid(Player who, EconomyResult refusal) {
        String key = refusal.outcome() == EconomyResult.Outcome.NOT_ENOUGH
                ? "tpa.cannot-afford" : "tpa.payment-failed";
        messages.send(who, key, "price", Fees.format(refusal.amount()));
    }

    /** Both people are told what the trip will cost, when it costs anything. */
    private void tellThePrice(Player from, Player to, TpaKind kind) {
        Player traveller = kind == TpaKind.TO ? from : to;
        Player destination = kind == TpaKind.TO ? to : from;
        Money quoted = Fees.quote(TpaFees.TRIP, tripPriceFor(traveller, destination));
        if (!quoted.isPositive()) {
            return;
        }
        String price = Fees.format(quoted);
        messages.send(traveller, "tpa.price-you", "price", price);
        Player other = traveller.equals(from) ? to : from;
        messages.send(other, "tpa.price-them", "player", traveller.getName(), "price", price);
    }

    /** The refusal for somebody on a wait they could buy their way out of: the price, and a button. */
    private void offerToSkip(Player from, Player to, TpaKind kind) {
        UUID who = from.getUniqueId();
        UUID target = to.getUniqueId();
        String price = Fees.format(Fees.quote(TpaFees.SKIP, skipPriceFor(from)));
        Component line = messages.prefixed("tpa.too-soon-skippable",
                "time", waitLeft(who), "price", price);
        Component button = buttons.label("<green>[Skip the wait]")
                .tooltip("<gray>Pay <white>" + price + "</white> and ask now")
                .forOnly(who).expiringIn(OFFER_LIFETIME)
                .does(clicker -> onlineOf(clicker).ifPresent(asker ->
                        onlineOf(target).ifPresent(asked -> ask(asker, asked, kind, true))))
                .render();
        from.sendMessage(line.appendSpace().append(button));
    }

    private static final Duration OFFER_LIFETIME = Duration.ofSeconds(30);

    private boolean bypasses(Player who, String node) {
        return who.hasPermission(node) || (settings.operatorsBypass() && who.isOp());
    }

    /** Whether this player may ask again yet. */
    public boolean isReadyToAsk(Player who) {
        return bypasses(who, PermissionNodes.BYPASS_COOLDOWN) || waits.isReady(who.getUniqueId());
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
        // Standing through a wait when they quit: no trip, so no charge.
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

    private Optional<Player> onlineOf(UUID who) {
        return Optional.ofNullable(who).map(plugin.getServer()::getPlayer).filter(Player::isOnline);
    }

    /** What the traveller is told on the way. */
    private final class Arriving implements TravelWatcher {

        private final String towards;

        private Arriving(String towards) {
            this.towards = towards;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "tpa.warming-up", "player", towards, "seconds", secondsLeft);
        }

        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            TpaFees.Taken spent = fees.settle(traveller.getUniqueId());
            if (spent != null && spent.total().isPositive()) {
                messages.send(traveller, "tpa.paid", "price", Fees.format(spent.total()));
            }
            messages.send(traveller, "tpa.arrived", "player", towards);
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "player", towards);
            giveBack(traveller);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "player", towards);
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

    /**
     * This server's wording for each of Core's reasons.
     *
     * <p>A switch with no default, so a reason added to {@link TravelReason} is a compiler error here
     * rather than a line of English nobody notices has gone untranslated.
     */
    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "tpa.cancelled.moved";
            case HURT -> "tpa.cancelled.hurt";
            case ALREADY_TRAVELLING -> "tpa.already-travelling";
            case WORLD_MISSING -> "tpa.world-gone";
            case NOWHERE_SAFE -> "tpa.nowhere-safe";
            case COULD_NOT_CHECK -> "tpa.could-not-check";
            case TELEPORT_REFUSED -> "tpa.teleport-refused";
            case CANNOT_SCHEDULE -> "tpa.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "asking, answering, and the journey that follows";
    }
}
