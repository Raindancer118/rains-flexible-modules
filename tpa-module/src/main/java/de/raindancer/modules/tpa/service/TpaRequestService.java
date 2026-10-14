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
import de.raindancer.modules.tpa.store.TpaNetwork;
import de.raindancer.modules.tpa.store.TpaRequests;
import de.raindancer.modules.tpa.util.PermissionNodes;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.Presence;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /** Sends a player to another server and lands them at a place. */
    @FunctionalInterface
    public interface Hop {
        void send(Player player, String server, Arrivals.Destination destination);
    }

    private volatile TpaNetwork network;
    private volatile Hop hop;

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

    // ------------------------------------------------------------------------ the network

    /** Joins the network: players on other servers can be asked, and may ask. Answers the three calls. */
    public void connectNetwork(TpaNetwork network, Hop hop) {
        this.network = network;
        this.hop = hop;
        network.answer(TpaNetwork.ASK, (server, question) -> done(received(server, question)));
        network.answer(TpaNetwork.ACCEPT, this::acceptedElsewhere);
        network.answer(TpaNetwork.END, (server, question) -> done(ended(server, question)));
    }

    /** Somebody on another server by name — empty on a server of its own. */
    public Optional<Presence.Here> findElsewhere(String name) {
        TpaNetwork net = network;
        return net == null ? Optional.empty() : net.find(name);
    }

    /** Everybody visible on the other servers. */
    public java.util.List<Presence.Here> playersElsewhere() {
        TpaNetwork net = network;
        return net == null ? java.util.List.of() : net.everyoneElse();
    }

    private static CompletableFuture<Map<String, String>> done(Map<String, String> answer) {
        return CompletableFuture.completedFuture(answer);
    }

    private static Map<String, String> result(String what) {
        return Map.of("result", what);
    }

    private static Map<String, String> encode(Arrivals.Destination where) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("world", where.world());
        data.put("x", String.valueOf(where.x()));
        data.put("y", String.valueOf(where.y()));
        data.put("z", String.valueOf(where.z()));
        data.put("yaw", String.valueOf(where.yaw()));
        data.put("pitch", String.valueOf(where.pitch()));
        return data;
    }

    private static Arrivals.Destination decode(Map<String, String> data) {
        return new Arrivals.Destination(data.get("world"), Double.parseDouble(data.get("x")),
                Double.parseDouble(data.get("y")), Double.parseDouble(data.get("z")),
                Float.parseFloat(data.get("yaw")), Float.parseFloat(data.get("pitch")));
    }

    private static Arrivals.Destination destinationOf(Location where) {
        return new Arrivals.Destination(where.getWorld().getName(), where.getX(), where.getY(), where.getZ(),
                where.getYaw(), where.getPitch());
    }

    /** Says something to a player of this server, on their thread. */
    private void tell(UUID who, String key, Object... values) {
        onlineOf(who).ifPresent(player -> Scheduling.entity(plugin, player,
                () -> messages.send(player, key, values), () -> { }));
    }

    /** Tells the server somebody is on that a request between two players has ended, and how. */
    private void endElsewhere(String server, UUID asker, UUID asked, String how, String name) {
        TpaNetwork net = network;
        if (net != null) {
            net.ask(server, TpaNetwork.END, Map.of("asker", asker.toString(), "asked", asked.toString(),
                    "how", how, "name", name));
        }
    }

    /** A request this player made has been pushed aside or taken back: tell the one asked, wherever they are. */
    private void bump(TpaRequest displaced, Player from) {
        if (onlineOf(displaced.to()).isPresent()) {
            tell(displaced.to(), "tpa.withdrawn-by-asker", "player", from.getName());
            return;
        }
        TpaNetwork net = network;
        if (net != null) {
            net.serverOf(displaced.to()).ifPresent(server ->
                    endElsewhere(server, from.getUniqueId(), displaced.to(), "withdrawn", from.getName()));
        }
    }

    /** A request ended because {@code leaving} left; the other side may be on another server. */
    public void leftWhileAsked(TpaRequest ended, Player leaving) {
        UUID other = ended.from().equals(leaving.getUniqueId()) ? ended.to() : ended.from();
        TpaNetwork net = network;
        if (net != null && onlineOf(other).isEmpty()) {
            net.serverOf(other).ifPresent(server ->
                    endElsewhere(server, ended.from(), ended.to(), "left", leaving.getName()));
        }
    }

    /**
     * Asks somebody on another server. The request is kept here too, so asking again, taking it back and its
     * running out work as for anybody; the one asked holds the one that can be answered.
     */
    public boolean askElsewhere(Player from, Presence.Here there, TpaKind kind) {
        TpaNetwork net = network;
        if (net == null) {
            messages.send(from, "tpa.no-such-player", "player", there.name());
            return false;
        }
        TpaSettings now = settings;
        TpaAskingRule.Verdict verdict = asking.check(from.getUniqueId(), there.player(),
                prefs.of(there.player()), now.allowCrossWorld(), from.hasPermission(PermissionNodes.BYPASS_TOGGLE),
                requests.has(from.getUniqueId(), there.player()), !isReadyToAsk(from));
        if (!verdict.isFine()) {
            messages.send(from, verdict.messageKey(), "player", there.name(), "time", waitLeft(from.getUniqueId()));
            return false;
        }
        TpaRequests.Outcome outcome = requests.ask(from.getUniqueId(), there.player(), kind);
        outcome.displaced().ifPresent(displaced -> bump(displaced, from));
        if (outcome.request().isEmpty()) {
            messages.send(from, "tpa.already-asked", "player", there.name());
            return false;
        }
        TpaRequest made = outcome.request().get();
        prefs.seen(from);
        prefs.seen(there.player(), there.name());
        Map<String, String> question = new LinkedHashMap<>();
        question.put("from", from.getUniqueId().toString());
        question.put("fromName", from.getName());
        question.put("to", there.player().toString());
        question.put("kind", kind.name());
        question.put("bypass", String.valueOf(from.hasPermission(PermissionNodes.BYPASS_TOGGLE)));
        net.ask(there.server(), TpaNetwork.ASK, question).thenAccept(answer -> {
            String how = answer.map(reply -> reply.getOrDefault("result", "")).orElse("unreachable");
            if (how.equals("asked")) {
                waits.start(from.getUniqueId());
                tell(from.getUniqueId(), "tpa.asked", "player", there.name(), "seconds", now.requestStanding());
                Scheduling.entityLater(plugin, from, sweepTicks(made), this::sweep);
                return;
            }
            requests.withdraw(from.getUniqueId(), there.player());
            tell(from.getUniqueId(), switch (how) {
                case "not-accepting" -> "tpa.not-accepting";
                case "already" -> "tpa.already-asked";
                case "offline" -> "tpa.no-such-player";
                default -> "tpa.server-unreachable";
            }, "player", there.name());
        });
        return true;
    }

    /** On the server of the one asked: a request from a player of another server arrives. */
    private Map<String, String> received(String server, Map<String, String> question) {
        try {
            UUID from = UUID.fromString(question.get("from"));
            UUID to = UUID.fromString(question.get("to"));
            TpaKind kind = TpaKind.valueOf(question.get("kind"));
            String fromName = question.getOrDefault("fromName", "somebody");
            TpaNetwork net = network;
            if (net == null || !net.hostedBy(from, server)) {
                return result("gone");
            }
            Player target = onlineOf(to).orElse(null);
            if (target == null) {
                return result("offline");
            }
            prefs.seen(from, fromName);
            boolean bypass = Boolean.parseBoolean(question.get("bypass"));
            if (!bypass && !prefs.of(to).mayBeAskedBy(from)) {
                return result("not-accepting");
            }
            TpaRequests.Outcome outcome = requests.ask(from, to, kind);
            outcome.displaced().ifPresent(displaced ->
                    tell(displaced.to(), "tpa.withdrawn-by-asker", "player", fromName));
            if (outcome.request().isEmpty()) {
                return result("already");
            }
            Scheduling.entity(plugin, target, () -> announce(from, fromName, target, kind), () -> { });
            Scheduling.entityLater(plugin, target, sweepTicks(outcome.request().get()), this::sweep);
            return result("asked");
        } catch (RuntimeException unreadable) {
            return result("gone");
        }
    }

    /** On the server of whoever asked: the one asked has accepted. Their server may say so only for them. */
    private CompletableFuture<Map<String, String>> acceptedElsewhere(String server, Map<String, String> question) {
        try {
            UUID asker = UUID.fromString(question.get("asker"));
            UUID target = UUID.fromString(question.get("target"));
            String targetName = question.getOrDefault("targetName", "somebody");
            TpaNetwork net = network;
            if (net == null || !net.hostedBy(target, server)) {
                return done(result("gone"));
            }
            Player here = onlineOf(asker).orElse(null);
            if (here == null) {
                return done(result("offline"));
            }
            Optional<TpaRequest> taken = requests.take(target, asker);
            if (taken.isEmpty()) {
                return done(result("gone"));
            }
            CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
            Scheduling.entity(plugin, here, () -> {
                if (taken.get().kind() == TpaKind.HERE) {
                    tell(asker, "tpa.they-accepted", "player", targetName);
                    Map<String, String> where = encode(destinationOf(here.getLocation()));
                    where.put("result", "ok");
                    answer.complete(where);
                    return;
                }
                if (travel.isTravelling(asker) || fees.holding(asker)) {
                    answer.complete(result("busy"));
                    return;
                }
                TpaFees.Taken paid = chargeAcrossServers(here);
                if (paid == null) {
                    answer.complete(result("cannot-pay"));
                    return;
                }
                messages.send(here, "tpa.they-accepted", "player", targetName);
                goElsewhere(here, server, decode(question), targetName, paid);
                answer.complete(result("ok"));
            }, () -> answer.complete(result("offline")));
            return answer;
        } catch (RuntimeException unreadable) {
            return done(result("gone"));
        }
    }

    /** One side of a request ended on another server: a withdrawal, a refusal, or somebody leaving. */
    private Map<String, String> ended(String server, Map<String, String> question) {
        try {
            UUID asker = UUID.fromString(question.get("asker"));
            UUID asked = UUID.fromString(question.get("asked"));
            String how = question.getOrDefault("how", "");
            String name = question.getOrDefault("name", "somebody");
            TpaNetwork net = network;
            if (net == null) {
                return result("gone");
            }
            switch (how) {
                case "withdrawn" -> {
                    if (net.hostedBy(asker, server) && requests.withdraw(asker, asked).isPresent()) {
                        tell(asked, "tpa.withdrawn-by-asker", "player", name);
                    }
                }
                case "refused" -> {
                    if (net.hostedBy(asked, server) && requests.withdraw(asker, asked).isPresent()) {
                        tell(asker, "tpa.they-refused", "player", name);
                    }
                }
                case "left" -> {
                    if (requests.withdraw(asker, asked).isPresent()) {
                        tell(onlineOf(asker).isPresent() ? asker : asked, "tpa.other-left", "player", name);
                    }
                }
                default -> {
                    return result("gone");
                }
            }
            return result("ok");
        } catch (RuntimeException unreadable) {
            return result("gone");
        }
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
        outcome.displaced().ifPresent(displaced -> bump(displaced, from));

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
        if (announce(from.getUniqueId(), from.getName(), to, kind)) {
            tellThePrice(from, to, kind);
        }
        sweepAfter(from, made.get());
        return true;
    }

    /**
     * Puts a made request in front of the person asked: the words with [Accept] [Deny] under them — or, for
     * somebody who switched auto-accept on, straight into answering it.
     *
     * <p>To chat, never the action bar: this has to still be there when they come back to the keyboard, and
     * an action bar is gone in three seconds. The buttons are bound to this request alone: a click after
     * somebody has asked again, or after this one has lapsed, answers whatever request stands at that moment
     * through accept()/deny() themselves — both already read from the store fresh, exactly as bare /tpaccept
     * does.
     *
     * @return false when it was accepted on the spot, so there is nothing left to quote a price for
     */
    private boolean announce(UUID fromId, String fromName, Player to, TpaKind kind) {
        TpaSettings now = settings;
        if (prefs.of(to.getUniqueId()).autoAccept()) {
            messages.send(to, "tpa.auto-accepted", "player", fromName);
            accept(to, fromId);
            return false;
        }
        Component asked = messages.prefixed(kind == TpaKind.TO ? "tpa.asked-you-to" : "tpa.asked-you-here",
                "player", fromName,
                "seconds", now.requestStanding());
        Component question = buttons.ask(to.getUniqueId(), Duration.ofSeconds(now.requestStanding()),
                clicker -> onlineOf(clicker).ifPresent(answerer -> accept(answerer, fromId)),
                clicker -> onlineOf(clicker).ifPresent(answerer -> deny(answerer, fromId)));
        to.sendMessage(asked.appendNewline().append(question));
        return true;
    }

    /**
     * {@code /tpahere @a}: asks everybody who could be asked to come to the asker, with one wait between
     * requests for the lot. Whoever could not be asked one by one — switched off, blocked, another world,
     * somebody the asker cannot see — is passed over without a word to them, and counted for the asker.
     *
     * @return how many were asked
     */
    public int askEveryone(Player from) {
        if (!isReadyToAsk(from)) {
            messages.send(from, TpaAskingRule.Verdict.TOO_SOON.messageKey(), "player", "", "time", waitLeft(from.getUniqueId()));
            return 0;
        }
        TpaSettings now = settings;
        java.util.List<Player> askable = new java.util.ArrayList<>();
        int passedOver = 0;
        for (Player to : from.getServer().getOnlinePlayers()) {
            if (to.equals(from)) {
                continue;
            }
            if (!vanish.canSee(from.getUniqueId(), to.getUniqueId())) {
                continue;
            }
            boolean reachable = now.allowCrossWorld() || from.getWorld().equals(to.getWorld());
            TpaAskingRule.Verdict verdict = asking.check(from.getUniqueId(), to.getUniqueId(),
                    prefs.of(to.getUniqueId()), reachable, from.hasPermission(PermissionNodes.BYPASS_TOGGLE),
                    requests.has(from.getUniqueId(), to.getUniqueId()), false);
            if (verdict.isFine()) {
                askable.add(to);
            } else {
                passedOver++;
            }
        }
        if (askable.isEmpty()) {
            messages.send(from, "tpa.nobody-to-ask");
            return 0;
        }
        TpaRequests.Batch batch = requests.askAll(from.getUniqueId(),
                askable.stream().map(Player::getUniqueId).toList(), TpaKind.HERE);
        batch.displaced().forEach(displaced -> bump(displaced, from));
        if (batch.made().isEmpty()) {
            messages.send(from, "tpa.nobody-to-ask");
            return 0;
        }
        waits.start(from.getUniqueId());
        prefs.seen(from);
        messages.send(from, "tpa.asked-everyone",
                "count", batch.made().size(),
                "passed", passedOver,
                "seconds", now.requestStanding());
        for (TpaRequest made : batch.made()) {
            onlineOf(made.to()).ifPresent(to -> {
                prefs.seen(to);
                announce(from.getUniqueId(), from.getName(), to, TpaKind.HERE);
            });
        }
        sweepAfter(from, batch.made().get(0));
        return batch.made().size();
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
        Scheduling.entityLater(plugin, asker, sweepTicks(made), this::sweep);
    }

    private static long sweepTicks(TpaRequest made) {
        return Math.max(1, (made.expiresAt() - System.currentTimeMillis()) / 50L) + GRACE_TICKS;
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
            Optional<String> there = onlineOf(request.from()).isPresent() || network == null
                    ? Optional.empty() : network.serverOf(request.from());
            if (there.isPresent()) {
                return acceptElsewhere(answering, request, there.get());
            }
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
        Optional<Player> asker = onlineOf(taken.get().from());
        asker.ifPresent(here -> messages.send(here, "tpa.they-refused", "player", answering.getName()));
        if (asker.isEmpty() && network != null) {
            network.serverOf(taken.get().from()).ifPresent(server -> endElsewhere(server, taken.get().from(),
                    answering.getUniqueId(), "refused", answering.getName()));
        }
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
        java.util.List<TpaRequest> withdrawn = requests.withdrawAll(who.getUniqueId());
        if (withdrawn.isEmpty()) {
            messages.send(who, "tpa.nothing-to-take-back");
            return false;
        }
        if (withdrawn.size() == 1) {
            messages.send(who, "tpa.took-it-back", "player", prefs.nameOf(withdrawn.get(0).to()));
        } else {
            messages.send(who, "tpa.took-them-back", "count", withdrawn.size());
        }
        for (TpaRequest each : withdrawn) {
            bump(each, who);
        }
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

    /**
     * The answerer accepts a request from somebody on another server. Their own server asks the asker's, which
     * holds the asker and can say where they are; whoever travels is charged and waits where they are, then is
     * sent across.
     */
    private boolean acceptElsewhere(Player answering, TpaRequest request, String server) {
        TpaNetwork net = network;
        boolean answererTravels = request.kind() == TpaKind.HERE;
        String askerName = prefs.nameOf(request.from());
        TpaFees.Taken paid = TpaFees.Taken.NOTHING;
        if (answererTravels) {
            if (travel.isTravelling(answering.getUniqueId()) || fees.holding(answering.getUniqueId())) {
                messages.send(answering, "tpa.already-travelling");
                return false;
            }
            paid = chargeAcrossServers(answering);
            if (paid == null) {
                return false;
            }
        }
        Map<String, String> question = new LinkedHashMap<>();
        question.put("asker", request.from().toString());
        question.put("target", answering.getUniqueId().toString());
        question.put("targetName", answering.getName());
        if (!answererTravels) {
            question.putAll(encode(destinationOf(answering.getLocation())));
        }
        TpaFees.Taken held = paid;
        net.ask(server, TpaNetwork.ACCEPT, question).thenAccept(answer -> Scheduling.entity(plugin, answering, () -> {
            String how = answer.map(reply -> reply.getOrDefault("result", "")).orElse("unreachable");
            if (!how.equals("ok")) {
                fees.refund(answering.getUniqueId(), held);
                messages.send(answering, switch (how) {
                    case "busy" -> "tpa.they-are-travelling";
                    case "cannot-pay" -> "tpa.traveller-cannot-pay";
                    case "unreachable" -> "tpa.server-unreachable";
                    default -> "tpa.no-longer-online";
                }, "player", askerName);
                return;
            }
            messages.send(answering, "tpa.you-accepted", "player", askerName);
            if (answererTravels) {
                goElsewhere(answering, answer.get().getOrDefault("server", server), decode(answer.get()),
                        askerName, held);
            }
        }, () -> fees.refund(answering.getUniqueId(), held)));
        return true;
    }

    /** The server the asker is on is where the answerer lands; the answer carries the place. */
    private void goElsewhere(Player traveller, String server, Arrivals.Destination where, String towards,
                             TpaFees.Taken paid) {
        TpaSettings now = settings;
        int warmup = bypasses(traveller, PermissionNodes.BYPASS_WARMUP) ? 0 : now.warmup();
        // Waits on the spot: nothing here is a place to land, and nobody on a lead crosses servers.
        Trip trip = Trip.to(towards).after(warmup).exactly();
        if (!fees.hold(traveller.getUniqueId(), paid)) {
            fees.refund(traveller.getUniqueId(), paid);
            messages.send(traveller, "tpa.already-travelling");
            return;
        }
        travel.go(traveller, traveller.getLocation(), trip, new Arriving(towards, server, where));
    }

    /** Takes the price of a trip to another server — the cross-world price, as the distance means nothing. */
    private TpaFees.Taken chargeAcrossServers(Player traveller) {
        Money price = traveller.hasPermission(PermissionNodes.BYPASS_FEE) ? Money.ZERO
                : fees.trip(false, Double.NaN);
        TpaFees.Charge charge = fees.charge(traveller.getUniqueId(), price, TpaFees.TRIP, Money.ZERO);
        if (charge.paid()) {
            return charge.taken();
        }
        tellWhyNotPaid(traveller, charge.refusal());
        return null;
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
        /** Where to send them on when the place is on another server; null for one on this server. */
        private final String server;
        private final Arrivals.Destination elsewhere;

        private Arriving(String towards) {
            this(towards, null, null);
        }

        private Arriving(String towards, String server, Arrivals.Destination elsewhere) {
            this.towards = towards;
            this.server = server;
            this.elsewhere = elsewhere;
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
            if (elsewhere != null) {
                messages.send(traveller, "tpa.travelling-away", "player", towards, "server", server);
                hop.send(traveller, server, elsewhere);
                return;
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
