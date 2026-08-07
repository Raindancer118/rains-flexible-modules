package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Material;

import java.time.Duration;
import java.util.List;

/**
 * Three mobility items, registered with RainsCore rather than implemented here: the grappling hook,
 * repulse, and leap.
 *
 * <h2>What each one does</h2>
 * <ul>
 *   <li><b>The grappling hook</b> — pulls the holder towards a block they are looking at, in a straight
 *       line, for as long as the pull lasts. Aimed at open sky, there is nothing to pull towards, and it
 *       declines rather than picking an arbitrary point in the air — see the interface note.</li>
 *   <li><b>Repulse</b> — a wind-charge shockwave that shoves everybody nearby outward and slows them briefly.
 *       Crowd control rather than a personal escape, unlike the other two.</li>
 *   <li><b>Leap</b> — a catapult in whatever direction the holder is facing, with slow-falling applied so the
 *       landing does not undo the escape it just bought.</li>
 * </ul>
 *
 * <h2>Where Hermes' boots went</h2>
 * They were the fourth item here, ported as a right-click ability like the other three: a few seconds of
 * borrowed flight, consumed on use. Real feedback from testing turned them into what their name always
 * promised — actual boots, worn rather than clicked, with a flight budget for the round that only spends
 * while the holder is actually airborne. That is a passive, armour-slot-driven mechanic rather than a
 * triggered ability, so it belongs with its own service — {@code HermesBootsService} — rather than beside
 * three services whose whole shape is "Core dispatches a right click".
 *
 * <h2>Why there is no ItemListener here either</h2>
 * See {@link ArenaItemService}'s class note for the full argument: Core owns the cooldown, the trigger, and
 * the watching for a right-click; this module owns only what the item actually does, as a predicate that
 * declines rather than always succeeding. The source's version of these lived inside a single 1120-line
 * {@code CustomItems} enum, alongside nine other items that have nothing to do with mobility — cutting that
 * apart by what an item <em>is for</em>, rather than keeping one god-class, is the only structural change this
 * port makes on top of what Core already provides.
 *
 * <h2>Why every effect is a seam rather than Bukkit calls</h2>
 * None of these items can be tested against a running server, and none of them need to be: what they are
 * deciding — is there anything within range, how hard to push, how long to keep flight going — is arithmetic
 * and a boolean, not world state. The vector maths, the potion effects and the actual velocity changes belong
 * to whoever implements the seam, on whatever thread owns the player at that moment (the holder's own region
 * under Folia). This class never touches {@code org.bukkit.entity.Player}, {@code World}, {@code Location} or
 * {@code Vector} for exactly that reason.
 *
 * <h2>Why these three have cooldowns and the source's versions had none</h2>
 * In the source plugin every one of these was a sponsor drop: a single-use consumable, gone the moment it
 * fired. Core's item model has no notion of "consumed once" for an ability-bearing item — an item here is
 * durable equipment with an ability attached, and a cooldown is what that model uses in place of scarcity.
 * Real feedback from testing: the cooldown itself earns its place, but the numbers below must not be an
 * un-turn-off-able default — {@link HungerGamesSettings#grapplingCooldownSeconds()} and its two siblings all
 * default to zero, so an upgrading server's tournament plays exactly as the source did unless somebody
 * deliberately sets one.
 */
public final class MobilityItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Pulls the holder towards whatever they are looking at. */
    public static final String GRAPPLING_HOOK = "grappling-hook";

    /** A wind-charge shockwave that shoves everybody nearby away. */
    public static final String REPULSE = "repulse";

    /** A catapult in the direction the holder is facing. */
    public static final String LEAP = "leap";

    /**
     * {@code hgpack}'s custom-model-data threshold for Leap's texture — see {@code HermesBootsService}'s
     * own constant for why this number and the pack must change together.
     */
    private static final int LEAP_MODEL_DATA = 1002;

    /**
     * How long leap protects the landing.
     *
     * <p>Four seconds of slow-falling, the source's value — chosen there, and kept here, because a catapult
     * that hands the holder straight back their own fall damage on landing has not actually helped them.
     */
    public static final Duration LEAP_SOFT_LANDING = Duration.ofSeconds(4);

    /**
     * How long a single grapple may pull before it lets go on its own.
     *
     * <p>Three seconds. Long enough to cross the range the hook can target at all (forty blocks, at the
     * default pull speed), short enough that aiming somewhere unreachable does not leave a holder stuck
     * flying towards it for longer than the pull itself is worth. Not a setting: it is a safety bound on
     * the mechanic, not a balance knob a server would tune per tournament.
     */
    public static final Duration GRAPPLING_MAX_PULL_DURATION = Duration.ofSeconds(3);

    /**
     * How close counts as arrived. Stopping the pull exactly at the target would plant the holder inside
     * the block they aimed at; a block and a half short lands them beside it instead.
     */
    public static final double GRAPPLING_ARRIVAL_DISTANCE = 1.5;

    /**
     * Pulling the holder towards a block they are looking at — a continuous flight along a straight line,
     * not a single shove. A grapple aimed at open sky, with nothing solid in range, has nothing to pull
     * towards and declines.
     */
    @FunctionalInterface
    public interface Grappling {

        /** @return whether there was a block within range to pull towards */
        boolean pullTowardsTarget(ItemUse use, double range, double speed, Duration maxDuration);
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
    private final Grappling grappling;
    private final Repulsion repulsion;
    private final Launching launching;

    private volatile HungerGamesSettings settings;

    public MobilityItemService(ItemAbilities abilities, CustomItems items,
                               Grappling grappling, Repulsion repulsion, Launching launching,
                               HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
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
     * Tells Core about all three items and all three abilities.
     *
     * <p>{@code defineIfAbsent} for the items, the same reasoning as {@link ArenaItemService#register()}: a
     * server owner may have re-skinned one of these in a menu, and booting over that edit would make it look
     * like it never saved. The abilities are registered outright, because they are code and a stale one is a
     * button that still does last release's thing.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, GRAPPLING_HOOK)
                .material(Material.FISHING_ROD)
                .name("<aqua>Grappling Hook")
                .lore(List.of(
                        "<gray>Right-click a block in range:",
                        "<gray>flies you there in a straight line."))
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
                .modelData(LEAP_MODEL_DATA)
                .build());

        HungerGamesSettings now = settings;
        abilities.register(ItemAbility.builder(PLUGIN, GRAPPLING_HOOK)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Pull towards a block you are looking at")
                .consumesItem()
                .cooldown(Duration.ofSeconds(now.grapplingCooldownSeconds()))
                .attempts(this::fireTheGrapplingHook)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, REPULSE)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Shove everybody nearby away")
                .consumesItem()
                .cooldown(Duration.ofSeconds(now.repulseCooldownSeconds()))
                .attempts(this::unleashRepulse)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, LEAP)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Catapult forwards, with a soft landing")
                .consumesItem()
                .cooldown(Duration.ofSeconds(now.leapCooldownSeconds()))
                .attempts(this::leap)
                .build());
    }

    // ==================== what the items do ====================

    /**
     * @return whether there was anything to grapple onto — {@code false} costs no cooldown, which is the
     *         whole reason this is {@code attempts(...)} rather than {@code does(...)}: a hook thrown at open
     *         sky has nothing to pull towards, and charging eight seconds for a shot that went nowhere is
     *         exactly the broken-feeling item the class note warns about.
     */
    boolean fireTheGrapplingHook(ItemUse use) {
        HungerGamesSettings current = settings;
        return grappling.pullTowardsTarget(use, current.grapplingRange(), current.grapplingPowerStrength(),
                GRAPPLING_MAX_PULL_DURATION);
    }

    /** @return whether the shockwave actually went off. */
    boolean unleashRepulse(ItemUse use) {
        HungerGamesSettings current = settings;
        return repulsion.shove(use, current.repulseRadius(), current.repulseStrengthMultiplier(),
                Duration.ofSeconds(current.repulseSlowSeconds()));
    }

    /** @return whether the holder was actually launched. */
    boolean leap(ItemUse use) {
        HungerGamesSettings current = settings;
        return launching.launchForwards(use, current.leapPowerStrength(), LEAP_SOFT_LANDING);
    }

    @Override
    public String describe() {
        return "the grappling hook, repulse and leap, as abilities Core dispatches";
    }
}
