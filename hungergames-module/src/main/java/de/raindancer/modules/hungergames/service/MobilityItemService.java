package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.bukkit.Material;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * The four mobility items, registered with RainsCore rather than implemented here: Hermes' boots, the
 * grappling hook, repulse, and leap.
 *
 * <h2>What each one does</h2>
 * <ul>
 *   <li><b>Hermes' boots</b> — flight for a few seconds, with a warning sound before it cuts out. The source
 *       gave the holder no notice at all; falling out of the sky mid-sentence is not a design choice worth
 *       keeping, so a warning window is added here as a seam parameter rather than left to the implementer to
 *       remember.</li>
 *   <li><b>The grappling hook</b> — pulls the holder towards whatever they are looking at. Aimed at open sky,
 *       there is nothing to pull towards, and the source knew this (it declined when the resulting vector was
 *       almost zero) — the seam here keeps that same "nothing to grapple onto" answer rather than always
 *       succeeding.</li>
 *   <li><b>Repulse</b> — a wind-charge shockwave that shoves everybody nearby outward and slows them briefly.
 *       Crowd control rather than a personal escape, unlike the other three.</li>
 *   <li><b>Leap</b> — a catapult in whatever direction the holder is facing, with slow-falling applied so the
 *       landing does not undo the escape it just bought.</li>
 * </ul>
 *
 * <h2>Why there is no ItemListener here either</h2>
 * See {@link ArenaItemService}'s class note for the full argument: Core owns the cooldown, the trigger, and
 * the watching for a right-click; this module owns only what the item actually does, as a predicate that
 * declines rather than always succeeding. The source's version of these four lived inside a single 1120-line
 * {@code CustomItems} enum, alongside nine other items that have nothing to do with mobility — cutting that
 * apart by what an item <em>is for</em>, rather than keeping one god-class, is the only structural change this
 * port makes on top of what Core already provides.
 *
 * <h2>Why every effect is a seam rather than Bukkit calls</h2>
 * None of the four items can be tested against a running server, and none of them need to be: what they are
 * deciding — is there anything within range, how hard to push, how long to keep flight going — is arithmetic
 * and a boolean, not world state. The vector maths, the potion effects and the actual velocity changes belong
 * to whoever implements the seam, on whatever thread owns the player at that moment (the holder's own region
 * under Folia). This class never touches {@code org.bukkit.entity.Player}, {@code World}, {@code Location} or
 * {@code Vector} for exactly that reason.
 *
 * <h2>Why these four have cooldowns and the source's versions had none</h2>
 * In the source plugin every one of these was a sponsor drop: a single-use consumable, gone the moment it
 * fired. Core's item model has no notion of "consumed once" for an ability-bearing item — an item here is
 * durable equipment with an ability attached, and a cooldown is what that model uses in place of scarcity.
 * The numbers below are this port's own judgement call, not a source value carried over, and are documented
 * as such rather than dressed up as if they were.
 */
public final class MobilityItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Temporary flight, with a warning before it ends. */
    public static final String HERMES_BOOTS = "hermes-boots";

    /** Pulls the holder towards whatever they are looking at. */
    public static final String GRAPPLING_HOOK = "grappling-hook";

    /** A wind-charge shockwave that shoves everybody nearby away. */
    public static final String REPULSE = "repulse";

    /** A catapult in the direction the holder is facing. */
    public static final String LEAP = "leap";

    /**
     * How long Hermes' boots keep the holder airborne.
     *
     * <p>Four seconds, unchanged from the source. Long enough to clear a ravine or a wall of trees, short
     * enough that flight cannot be used to simply out-run the border instead.
     */
    public static final Duration HERMES_FLIGHT_DURATION = Duration.ofSeconds(4);

    /**
     * How long before flight ends the warning sound starts.
     *
     * <p>Three seconds. The source's countdown played this every second inside the warning window; the sound
     * itself is the implementer's concern, but the window's width is tuned here — long enough that a holder
     * mid-flight has time to pick a landing spot, short enough that most of the four seconds still feels like
     * uninterrupted flight rather than one long warning.
     */
    public static final Duration HERMES_WARNING_DURATION = Duration.ofSeconds(3);

    /**
     * How long a holder must wait to use Hermes' boots again.
     *
     * <p>Forty-five seconds — roughly ten times the flight itself lasts. Flight is the single strongest
     * mobility effect of the four (the others move a player once; this moves them continuously for four
     * seconds), so it is the one with the longest wait between uses.
     */
    public static final Duration HERMES_COOLDOWN = Duration.ofSeconds(45);

    /**
     * How far the grappling hook reaches.
     *
     * <p>Forty blocks, the source's value. Far enough to cross most gaps the arena puts in front of a
     * tribute; the seam is still free to find nothing within that range and decline.
     */
    public static final double GRAPPLING_RANGE = 40.0;

    /**
     * How hard the grappling hook pulls.
     *
     * <p>1.4, the source's value (its config held it as an integer tenth, 14, for a settings UI without
     * decimals — that scaling is no longer needed once the number lives in code as a {@code double}). The
     * source also added upward impulse on top of this so the holder does not simply fly into the wall they
     * grappled; that clamp belongs with the vector maths, in the seam's implementation, not here.
     */
    public static final double GRAPPLING_POWER = 1.4;

    /**
     * How long a holder must wait to fire the grappling hook again.
     *
     * <p>Eight seconds. Short enough that it still reads as a mobility tool rather than a one-shot escape,
     * long enough that it cannot be chained into continuous travel across the whole arena.
     */
    public static final Duration GRAPPLING_COOLDOWN = Duration.ofSeconds(8);

    /**
     * How far repulse's shockwave reaches.
     *
     * <p>Six blocks, the source's value — wide enough to clear a holder's immediate melee range, not so wide
     * that it reaches across an entire room.
     */
    public static final double REPULSE_RADIUS = 6.0;

    /**
     * How hard repulse throws everybody it catches.
     *
     * <p>1.2, the source's value (its config held it as the integer tenth 12, for the same reason as the
     * grappling hook's power above).
     */
    public static final double REPULSE_VELOCITY = 1.2;

    /**
     * How long repulse slows whoever it throws.
     *
     * <p>Two seconds, the source's value. Long enough that being caught in the blast costs a real beat of
     * lost ground, short enough that repulse buys an escape rather than crippling a fight on its own.
     */
    public static final Duration REPULSE_SLOW_DURATION = Duration.ofSeconds(2);

    /**
     * How long a holder must wait to use repulse again.
     *
     * <p>Twelve seconds. Repulse affects everybody nearby rather than just the holder, so it is priced above
     * the grappling hook and leap, which only ever move the person holding them.
     */
    public static final Duration REPULSE_COOLDOWN = Duration.ofSeconds(12);

    /**
     * How hard leap catapults the holder.
     *
     * <p>1.5, the source's value (again read out of its config as the integer tenth 15).
     */
    public static final double LEAP_POWER = 1.5;

    /**
     * How long leap protects the landing.
     *
     * <p>Four seconds of slow-falling, the source's value — chosen there, and kept here, because a catapult
     * that hands the holder straight back their own fall damage on landing has not actually helped them.
     */
    public static final Duration LEAP_SOFT_LANDING = Duration.ofSeconds(4);

    /**
     * How long a holder must wait to leap again.
     *
     * <p>Six seconds — the shortest of the four, because leap is the smallest single effect on offer: one
     * jump, one landing, nothing that touches anybody else or lasts beyond the fall.
     */
    public static final Duration LEAP_COOLDOWN = Duration.ofSeconds(6);

    /**
     * Keeping somebody airborne for a while, and warning them before it ends.
     *
     * <p>The scheduling that counts the seconds down and plays the warning belongs to whoever implements
     * this — this class only says how long to fly and how far ahead of the end to start warning.
     */
    @FunctionalInterface
    public interface Flight {

        /** @return whether flight actually started (declines if the holder could already fly, e.g. in Creative) */
        boolean fly(ItemUse use, Duration forHowLong, Duration warnBefore);
    }

    /** Pulling the holder towards wherever they are looking. */
    @FunctionalInterface
    public interface Grappling {

        /** @return whether there was anything within range to pull towards */
        boolean pullTowardsTarget(ItemUse use, double range, double power);
    }

    /** Shoving everybody within a radius of the holder away, and slowing them briefly. */
    @FunctionalInterface
    public interface Repulsion {

        /** @return whether the shockwave actually went off */
        boolean shove(ItemUse use, double radius, double velocity, Duration slowFor);
    }

    /** Launching the holder forwards, and softening whatever landing follows. */
    @FunctionalInterface
    public interface Launching {

        /** @return whether the holder was actually launched */
        boolean launchForwards(ItemUse use, double power, Duration softLanding);
    }

    private final ItemAbilities abilities;
    private final CustomItems items;
    private final Supplier<GamePhase> phase;
    private final Flight flight;
    private final Grappling grappling;
    private final Repulsion repulsion;
    private final Launching launching;

    /**
     * Not read yet — none of the four numbers above come from a config page today. Held anyway, because
     * {@link IHungerGamesService} asks every service to take settings whether or not it currently reads
     * them, and the day a server owner wants leap's power tunable, this is where that read goes rather than
     * a second constructor threaded through every caller.
     */
    private volatile HungerGamesSettings settings;

    public MobilityItemService(ItemAbilities abilities, CustomItems items, Supplier<GamePhase> phase,
                               Flight flight, Grappling grappling, Repulsion repulsion, Launching launching,
                               HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
        this.phase = phase;
        this.flight = flight;
        this.grappling = grappling;
        this.repulsion = repulsion;
        this.launching = launching;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Tells Core about all four items and all four abilities.
     *
     * <p>{@code defineIfAbsent} for the items, the same reasoning as {@link ArenaItemService#register()}: a
     * server owner may have re-skinned one of these in a menu, and booting over that edit would make it look
     * like it never saved. The abilities are registered outright, because they are code and a stale one is a
     * button that still does last release's thing.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, HERMES_BOOTS)
                .material(Material.FEATHER)
                .name("<yellow>Hermes' Boots")
                .lore(List.of(
                        "<gray>Right-click: fly for " + HERMES_FLIGHT_DURATION.toSeconds() + "s.",
                        "<dark_gray>A warning sound plays before it ends."))
                .glowing(true)
                .ability(HERMES_BOOTS)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, GRAPPLING_HOOK)
                .material(Material.FISHING_ROD)
                .name("<aqua>Grappling Hook")
                .lore(List.of(
                        "<gray>Right-click: pulls you towards",
                        "<gray>whatever you are looking at."))
                .glowing(true)
                .ability(GRAPPLING_HOOK)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, REPULSE)
                .material(Material.WIND_CHARGE)
                .name("<white>Repulse")
                .lore(List.of(
                        "<gray>Right-click: shoves everybody nearby",
                        "<gray>away, and slows them briefly."))
                .glowing(true)
                .ability(REPULSE)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, LEAP)
                .material(Material.SLIME_BLOCK)
                .name("<green>Leap")
                .lore(List.of(
                        "<gray>Right-click: catapults you forwards,",
                        "<gray>with a soft landing."))
                .glowing(true)
                .ability(LEAP)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, HERMES_BOOTS)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Fly for a few seconds, with a warning before it ends")
                .consumesItem()
                .cooldown(HERMES_COOLDOWN)
                .attempts(this::flyLikeHermes)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, GRAPPLING_HOOK)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Pull towards whatever you are looking at")
                .consumesItem()
                .cooldown(GRAPPLING_COOLDOWN)
                .attempts(this::fireTheGrapplingHook)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, REPULSE)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Shove everybody nearby away")
                .consumesItem()
                .cooldown(REPULSE_COOLDOWN)
                .attempts(this::unleashRepulse)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, LEAP)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Catapult forwards, with a soft landing")
                .consumesItem()
                .cooldown(LEAP_COOLDOWN)
                .attempts(this::leap)
                .build());
    }

    // ==================== what the items do ====================

    /**
     * @return whether flight actually started — {@code false} costs the holder no cooldown, which matters
     *         for whoever can already fly (Creative, Spectator): turning that off again when the borrowed
     *         flight timed out would be a worse bug than declining now.
     */
    boolean flyLikeHermes(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return flight.fly(use, HERMES_FLIGHT_DURATION, HERMES_WARNING_DURATION);
    }

    /**
     * @return whether there was anything to grapple onto — {@code false} costs no cooldown, which is the
     *         whole reason this is {@code attempts(...)} rather than {@code does(...)}: a hook thrown at open
     *         sky has nothing to pull towards, and charging eight seconds for a shot that went nowhere is
     *         exactly the broken-feeling item the class note warns about.
     */
    boolean fireTheGrapplingHook(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return grappling.pullTowardsTarget(use, GRAPPLING_RANGE, GRAPPLING_POWER);
    }

    /** @return whether the shockwave actually went off. */
    boolean unleashRepulse(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return repulsion.shove(use, REPULSE_RADIUS, REPULSE_VELOCITY, REPULSE_SLOW_DURATION);
    }

    /** @return whether the holder was actually launched. */
    boolean leap(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return launching.launchForwards(use, LEAP_POWER, LEAP_SOFT_LANDING);
    }

    /**
     * Whether a round is actually on.
     *
     * <p>Asked per use rather than once at registration, the same reasoning as {@link ArenaItemService}: a
     * grappling hook that still works between rounds pulls somebody towards a point on a server they are not
     * playing a game with, and Hermes' boots handing out free flight in the lobby is worse than a broken
     * item.
     */
    boolean duringARound() {
        return phase.get() == GamePhase.RUNNING;
    }

    @Override
    public String describe() {
        return "the four mobility items, as abilities Core dispatches";
    }
}
