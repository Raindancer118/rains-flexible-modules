package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
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

    /** The wait between one player's requests. Core's, so two clicks in a millisecond cannot both pass. */
    private final Cooldowns<UUID> waits = new Cooldowns<>();

    private volatile TpaSettings settings;

    public TpaRequestService(Plugin plugin, TpaRequests requests, TpaPrefsService prefs,
                             TpaAskingRule asking, Travel travel, Messages messages,
                             TpaSettings settings) {
        this.plugin = plugin;
        this.requests = requests;
        this.prefs = prefs;
        this.asking = asking;
        this.travel = travel;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh;
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
        TpaSettings now = settings;
        boolean reachable = now.allowCrossWorld() || from.getWorld().equals(to.getWorld());
        boolean mayBypass = from.hasPermission(PermissionNodes.BYPASS_TOGGLE);

        TpaAskingRule.Verdict verdict = asking.check(from.getUniqueId(), to.getUniqueId(),
                prefs.of(to.getUniqueId()), reachable, mayBypass,
                requests.has(from.getUniqueId(), to.getUniqueId()),
                !isReadyToAsk(from));
        if (!verdict.isFine()) {
            messages.send(from, verdict.messageKey(),
                    "player", to.getName(),
                    "time", waitLeft(from.getUniqueId()));
            return false;
        }

        // Whatever they had asked before is pushed aside — and the person who was waiting on it has to
        // be told, or they go on waiting to answer a request that no longer exists.
        requests.displacedBy(from.getUniqueId(), to.getUniqueId(), kind)
                .ifPresent(displaced -> onlineOf(displaced.to()).ifPresent(bumped ->
                        messages.send(bumped, "tpa.withdrawn-by-asker", "player", from.getName())));

        Optional<TpaRequest> made = requests.put(from.getUniqueId(), to.getUniqueId(), kind);
        if (made.isEmpty()) {
            // Only reachable if something changed between the rule and here — another thread asked
            // first. Saying so beats silence.
            messages.send(from, "tpa.already-asked", "player", to.getName());
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
        messages.send(to, kind == TpaKind.TO ? "tpa.asked-you-to" : "tpa.asked-you-here",
                "player", from.getName(),
                "seconds", now.requestStanding());

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

        messages.send(answering, "tpa.you-accepted", "player", prefs.nameOf(request.from()));
        onlineOf(request.from()).filter(asker -> !asker.equals(answering))
                .ifPresent(asker -> messages.send(asker, "tpa.they-accepted",
                        "player", answering.getName()));

        send(traveller, destination, request);
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
    private void send(Player traveller, Player destination, TpaRequest request) {
        TpaSettings now = settings;
        int warmup = bypasses(traveller, PermissionNodes.BYPASS_WARMUP) ? 0 : now.warmup();

        Trip trip = Trip.to(destination.getName())
                .after(warmup)
                .bringing(Companions.WHAT_YOU_LEAD);
        Location whereTheyAre = destination.getLocation();
        travel.go(traveller, whereTheyAre, trip, new Arriving(destination.getName()));
    }

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

    /** Forgets a player's wait. Called when they log out. */
    public void forget(UUID who) {
        waits.forget(who);
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
            messages.send(traveller, "tpa.arrived", "player", towards);
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "player", towards);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "player", towards);
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
