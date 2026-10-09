package de.raindancer.modules.farmworld.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.world.time.Times;
import net.kyori.adventure.text.Component;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import de.raindancer.modules.farmworld.model.Arrival;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.core.world.teleport.Scatter;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.rules.FarmEntryRule;
import de.raindancer.modules.farmworld.store.FarmPasses;
import de.raindancer.modules.farmworld.store.FarmWorldCatalogue;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

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
    private final Cooldowns<UUID> between;

    /**
     * Where the next arrival lands.
     *
     * <p>Held rather than made per trip so that a test can hand in a known sequence. Not seeded from the
     * world's seed on purpose: two players arriving in the same farm world in the same second should not
     * land in the same place, and a seed derived from anything about the world is how they would.
     */
    private final Random random;

    private final FarmEntryRule entryRule = new FarmEntryRule();
    private final FarmFees fees = new FarmFees(null);
    private final FarmPasses passes;
    private final ChatButtons buttons;

    private volatile FarmWorldSettings settings;

    /** How long a day pass lasts, from the trip it was bought for. */
    private static final Duration PASS_LENGTH = Duration.ofHours(24);

    /** How long the offer's buttons can be clicked. */
    private static final Duration OFFER_LIFETIME = Duration.ofSeconds(30);

    public FarmTravelService(FarmWorldCatalogue catalogue, Travel travel, FarmAccessRule access,
                             Messages messages, Effects effects, FarmWorldSettings settings,
                             Random random) {
        this(catalogue, travel, access, messages, effects, settings, random, null);
    }

    /**
     * The same, with the clock the wait between trips is measured against handed in.
     *
     * @param clock milliseconds, and only ever read — null takes the system clock, which is what a
     *              running server wants. A test hands in one it can move itself
     */
    public FarmTravelService(FarmWorldCatalogue catalogue, Travel travel, FarmAccessRule access,
                             Messages messages, Effects effects, FarmWorldSettings settings,
                             Random random, LongSupplier clock) {
        this(catalogue, travel, access, messages, effects, settings, random, clock, null, null);
    }

    /**
     * The same, with the day passes and the chat buttons the entry offer is drawn with.
     *
     * @param passes  null means passes cannot be held, so buying one grants nothing
     * @param buttons null draws the offer as plain text with no buttons
     */
    public FarmTravelService(FarmWorldCatalogue catalogue, Travel travel, FarmAccessRule access,
                             Messages messages, Effects effects, FarmWorldSettings settings,
                             Random random, LongSupplier clock, FarmPasses passes,
                             ChatButtons buttons) {
        this.passes = passes;
        this.buttons = buttons;
        this.between = clock == null ? new Cooldowns<>() : new Cooldowns<>(clock);
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
        fees.settings(fresh);
    }

    /** The wait, for a screen that wants to say how long is left. */
    public Cooldowns<UUID> waits() {
        return between;
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
        between.sweep();
        // Standing through a warm-up when they quit: no trip, so no charge.
        fees.refundHeld(who);
    }

    /** Gives back everything paid for trips that never ended — for the module stopping. */
    public void refundPending() {
        fees.refundAllHeld();
    }

    /**
     * What entering costs this player, as lines for a button: nothing when it is free.
     *
     * <p>A pass they already hold says so, since that is the answer to "what will this cost me".
     */
    public List<String> priceLines(Player who) {
        List<String> lines = new ArrayList<>();
        if (bypassesFees(who)) {
            return lines;
        }
        if (passes != null && passes.isActive(who.getUniqueId())) {
            lines.add("<green>Your day pass is running — free.");
            return lines;
        }
        Money entry = Fees.quote(FarmFees.ENTRY, fees.entry());
        Money pass = Fees.quote(FarmFees.PASS, fees.pass());
        if (entry.isPositive()) {
            lines.add("<gray>Costs <white>" + Fees.format(entry) + "</white> to enter.");
        }
        if (pass.isPositive()) {
            lines.add("<gray>" + (entry.isPositive() ? "Or " : "Costs ") + "<white>" + Fees.format(pass)
                    + "</white> for a pass: free entry for 24 hours.");
        }
        return lines;
    }

    private boolean bypassesFees(Player who) {
        return who.hasPermission(PermissionNodes.BYPASS_FEE);
    }

    // ------------------------------------------------------------------------ going

    /**
     * Sends this player into the farm world of this name, or tells them why not.
     *
     * <p>Every path out of here says something. A command that silently does nothing is one people type
     * four more times, and then report as broken.
     */
    public void goTo(Player traveller, String name, Arrival how) {
        goTo(traveller, name, how, FarmEntryRule.Choice.NONE);
    }

    private void goTo(Player traveller, String name, Arrival how, FarmEntryRule.Choice choice) {
        if (traveller == null) {
            return;
        }
        FarmWorldView farm = catalogue.byName(name).orElse(null);
        if (farm == null) {
            messages.send(traveller, "farmworlds.unknown", "name", String.valueOf(name));
            return;
        }
        go(traveller, farm, how, choice);
    }

    /** The same, when the farm world is already in hand — what a menu click has. */
    public void go(Player traveller, FarmWorldView farm, Arrival how) {
        go(traveller, farm, how, FarmEntryRule.Choice.NONE);
    }

    /**
     * @param choice which price they picked from the offer's buttons; {@code NONE} for a plain request,
     *               which is asked to choose when both prices are set
     */
    private void go(Player traveller, FarmWorldView farm, Arrival how, FarmEntryRule.Choice choice) {
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

        Arrival arrival = how == null ? Arrival.SPAWN : how;
        if (!arrival.isAllowedBy(settings.scatter())) {
            // Refused out loud rather than quietly turned into an ordinary trip. A command that silently
            // does something other than what was typed is one people type four more times.
            messages.send(traveller, "farmworlds.no-rtp");
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

        Money entry = bypassesFees(traveller) ? Money.ZERO : fees.entry();
        Money pass = bypassesFees(traveller) ? Money.ZERO : fees.pass();
        FarmEntryRule.Entry decision = entryRule.decide(entry, pass,
                passes != null && passes.isActive(traveller.getUniqueId()),
                bypassesFees(traveller), choice);
        if (decision == FarmEntryRule.Entry.CHOOSE) {
            offer(traveller, farm, arrival);
            play(traveller, Cues.COOLDOWN);
            return;
        }
        FarmFees.Taken taken = FarmFees.Taken.NOTHING;
        if (decision != FarmEntryRule.Entry.FREE) {
            boolean buying = decision == FarmEntryRule.Entry.BUY_PASS;
            FarmFees.Charge charge = fees.charge(traveller.getUniqueId(), buying ? pass : entry,
                    buying ? FarmFees.PASS : FarmFees.ENTRY);
            if (!charge.paid()) {
                tellWhyNotPaid(traveller, charge.refusal());
                refused(traveller);
                return;
            }
            taken = charge.taken();
            fees.hold(traveller.getUniqueId(), taken);
        }
        depart(traveller, farm, world, arrival);
    }

    private void tellWhyNotPaid(Player traveller, EconomyResult refusal) {
        String key = refusal.outcome() == EconomyResult.Outcome.NOT_ENOUGH
                ? "farmworlds.cannot-afford" : "farmworlds.payment-failed";
        messages.send(traveller, key, "price", Fees.format(refusal.amount()));
    }

    /**
     * Both prices are set: say so, with a button for each. The pass is the alternative, never the default.
     *
     * <p>Bound to this player alone and for a short while; a click comes back through {@code go} and so
     * asks every question again.
     */
    private void offer(Player traveller, FarmWorldView farm, Arrival how) {
        UUID who = traveller.getUniqueId();
        String entry = Fees.format(Fees.quote(FarmFees.ENTRY, fees.entry()));
        String pass = Fees.format(Fees.quote(FarmFees.PASS, fees.pass()));
        Component line = messages.prefixed("farmworlds.entry-offer",
                "name", farm.name(), "price", entry, "pass-price", pass);
        if (buttons == null) {
            traveller.sendMessage(line);
            return;
        }
        Component once = chooseButton("<green>[Pay " + entry + "]", "<gray>Enter once", who, farm, how,
                FarmEntryRule.Choice.ENTRY);
        Component day = chooseButton("<aqua>[24h pass " + pass + "]",
                "<gray>Enter any farm world free for 24 hours", who, farm, how,
                FarmEntryRule.Choice.PASS);
        traveller.sendMessage(line.appendNewline().append(once).appendSpace().append(day));
    }

    private Component chooseButton(String label, String tooltip, UUID who, FarmWorldView farm, Arrival how,
                                   FarmEntryRule.Choice choice) {
        return buttons.label(label).tooltip(tooltip).forOnly(who).expiringIn(OFFER_LIFETIME)
                .does(clicker -> {
                    Player clicked = org.bukkit.Bukkit.getPlayer(clicker);
                    if (clicked != null && clicked.isOnline()) {
                        goTo(clicked, farm.name(), how, choice);
                    }
                }).render();
    }

    private void depart(Player traveller, FarmWorldView farm, World world, Arrival how) {
        FarmWorldSettings now = settings;
        Location target = arrivalIn(world, farm, now, how);
        Trip trip = Trip.to(farm.name())
                .after(now.warmup())
                // Never .exactly(): a scattered point is a point nobody has looked at, and dropping
                // somebody into one unchecked is dropping them inside stone about as often as onto
                // grass. There is deliberately no setting for this — see FarmWorldSettings.
                .searching(now.arrivalRadius())
                .bringing(now.companions());
        travel.go(traveller, target, trip, new Wording(farm, how));
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
    private Location arrivalIn(World world, FarmWorldView farm, FarmWorldSettings now, Arrival how) {
        // The spawn unless somebody asked to be sent into the wild. The platform is there, so this is a
        // known good landing and Core's safety search has nothing to do.
        if (!how.isScattered()) {
            return world.getSpawnLocation();
        }
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
        private final Arrival how;

        private Wording(FarmWorldView farm, Arrival how) {
            this.farm = farm;
            this.how = how;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "farmworlds.warming-up",
                    "name", farm.name(), "seconds", secondsLeft);
            // The ding each second is Core's, for every teleport, in the traveller's own sound if they picked one.
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
            FarmFees.Taken spent = fees.settle(traveller.getUniqueId());
            if (spent != null && spent.amount().isPositive()) {
                if (spent.isPass()) {
                    // Starts now, not when it was paid for: a trip cancelled or failed never got here.
                    if (passes != null) {
                        passes.grant(traveller.getUniqueId(), PASS_LENGTH);
                    }
                    messages.send(traveller, "farmworlds.pass-bought",
                            "price", Fees.format(spent.amount()));
                } else {
                    messages.send(traveller, "farmworlds.paid", "price", Fees.format(spent.amount()));
                }
            }
            // No arrival cue here: Core's Travel plays it for every teleport, in the traveller's own choice.
            // Where they came out only matters when it was somewhere unpredictable. On the platform it is
            // the same three numbers every time, and printing them is noise.
            if (how.isScattered()) {
                messages.send(traveller, "farmworlds.arrived-wild",
                        "name", farm.name(),
                        "where", where.getBlockX() + ", " + where.getBlockZ());
            } else {
                messages.send(traveller, "farmworlds.arrived", "name", farm.name());
            }
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
            giveBack(traveller);
            // A cancelled warm-up is a refusal to the player, whatever it is called in Core: they stood still,
            // something happened, and they are not going. The sound has to say that.
            play(traveller, Cues.NO);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why), "name", farm.name());
            giveBack(traveller);
            play(traveller, Cues.NO);
        }

        private void giveBack(Player traveller) {
            FarmFees.Taken spent = fees.settle(traveller.getUniqueId());
            if (spent != null && spent.amount().isPositive()) {
                fees.refund(traveller.getUniqueId(), spent);
                messages.send(traveller, "farmworlds.refunded", "price", Fees.format(spent.amount()));
            }
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
