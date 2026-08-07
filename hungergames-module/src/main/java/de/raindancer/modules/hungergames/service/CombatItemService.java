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
 * Five sponsor combat items, registered with RainsCore rather than implemented here — the smoke bomb, the
 * medikit, the lightning strike, krückauwasser, and the aura of protection.
 *
 * <h2>Where these came from</h2>
 * The plugin this is ported from kept all thirteen of its custom items — these five and eight more — as
 * enum constants on one 1120-line {@code CustomItems} class, each with its own hand-rolled right-click check,
 * its own {@code ItemStack} builder, and (for three of them) its own {@code BukkitRunnable} for a countdown or
 * a repeating pulse. See {@link ArenaItemService}'s class note for the fuller account of what that cost the
 * original: an unevictable cooldown map, a charge count keyed on the item stack rather than the player, and an
 * ability that fired twice for one click. None of that is repeated here — {@link ItemAbilities} owns the
 * charge, {@link ItemTrigger} says what sets an item off, and Core's own listener does the watching for a
 * right click. What is left for this module is only what Core cannot know: what each item does.
 *
 * <h2>Why every item consumes itself rather than spending a cooldown or a charge</h2>
 * The source called every one of these "Einmalig" ("single use") in its lore and physically removed the item
 * from the stack once it fired. A cooldown would let the same physical item be used again once it had ticked
 * down, which is not what the lore promised.
 *
 * <p>Neither is {@code charges(1)}, which is what this said first and is worth writing down: Core counts
 * charges <em>per player</em>, for ever. So the first medikit somebody ever used spent the only charge they
 * would ever get, and every medikit a sponsor sent them afterwards was a melon slice — an item bought from
 * the shop, in the middle of a round, that did nothing at all. {@code consumesItem()} is the thing the lore
 * actually promises: the item is single use, the player is not.
 *
 * <p>Either way it only happens when the predicate below says the use really happened, so a smoke bomb
 * thrown between rounds, or a medikit that could not find its holder, costs nothing.
 *
 * <h2>Why the sustained effects (the storm, the aura) are not scheduled here</h2>
 * The source ran the lightning strike's staggered bolts and the aura's repeated pulses from a
 * {@code BukkitRunnable} inside the very enum constant that also built the item and read the config — the
 * three concerns tangled together in one place, on one thread, with no way to test any of it without a live
 * server. This class must not import Bukkit's scheduler, {@code Player}, {@code World} or {@code Location} at
 * all (see the seam interfaces below) — that is what makes it possible to prove the numbers below are the ones
 * actually used without starting a server. The staggering and the pulsing are therefore the seam
 * implementation's problem, on the other side of the interfaces declared here; this class only says <em>which</em>
 * numbers a strike or an aura runs with, and that it may only run during a round.
 */
public final class CombatItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Ability and item id: the smoke bomb. */
    public static final String SMOKE_BOMB = "smoke-bomb";

    /** Ability and item id: the medikit. */
    public static final String MEDIKIT = "medikit";

    /** Ability and item id: the lightning strike. */
    public static final String LIGHTNING_STRIKE = "lightning-strike";

    /** Ability and item id: krückauwasser. */
    public static final String KRUECKAUWASSER = "krueckauwasser";

    /** Ability and item id: the aura of protection. */
    public static final String AURA_OF_PROTECTION = "aura-of-protection";

    /**
     * How many of one of these a successful use costs its holder.
     *
     * <p>Every one of the five is "Einmalig" in the source's lore and is taken out of the stack on the
     * first use that actually did something. One, not "one per player" — see the class note.
     */
    public static final int ONE_ITEM_PER_USE = 1;

    // ==================== smoke bomb ====================

    /**
     * How far the fog and the blindness/slowness reach, in blocks.
     *
     * <p>Documented fallback and reference value only — the live value a smoke bomb actually uses comes from
     * {@link HungerGamesSettings#smokeBombRadius()} at the moment it is thrown. Kept here at the source's own
     * default ({@code SMOKE_RADIUS = 6.0}) so a reader has something to compare a tuned server against.
     */
    public static final double SMOKE_BOMB_RADIUS = 6.0;

    /**
     * How long a caught enemy is blinded and slowed.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#smokeBombEnemyDuration()}, in seconds, at the moment the bomb goes off.
     * Kept here at the source's own default ({@code SMOKE_BOMB_ENEMY_DURATION}, six seconds of ticks); the
     * shipped settings default is actually three, a season of real tournaments having settled on it.
     */
    public static final Duration SMOKE_BOMB_ENEMY_EFFECT_DURATION = Duration.ofSeconds(6);

    /**
     * How long the thrower is themselves fully invisible, armour included.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#smokeBombInvisSeconds()}. Kept here at the source's own default
     * ({@code SMOKE_INVIS_SECONDS}).
     */
    public static final Duration SMOKE_BOMB_INVISIBILITY_DURATION = Duration.ofSeconds(3);

    // ==================== medikit ====================

    /**
     * How long Regeneration lasts once the medikit is used.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#medikitRegenSeconds()}. Kept here at the source's own default.
     *
     * <p>The cast time before any of it lands is {@link HungerGamesSettings#medikitCountdownSeconds()} and is
     * passed to {@link Medicine#treat} rather than applied here: it needs a scheduler and a damage listener,
     * and both are on the other side of that seam in {@code MedikitCountdownService}.
     */
    public static final Duration MEDIKIT_REGENERATION_DURATION = Duration.ofSeconds(6);

    /**
     * Regeneration's amplifier, derived from {@link HungerGamesSettings#medikitRegenLevel()} at the moment
     * the medikit is used — Bukkit's potion levels count from one, {@link org.bukkit.potion.PotionEffect}'s
     * amplifier from zero, so the setting's potion level 2 becomes amplifier 1. Kept here as a documented
     * fallback and reference value at the source's own default.
     */
    public static final int MEDIKIT_REGENERATION_AMPLIFIER = 1;

    /**
     * How long the medikit's extra hearts (Absorption) last.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#medikitAbsorptionSeconds()}. Kept here at the source's own default: a whole
     * minute, because the medikit is described in the source as "the sponsor's rescue anchor".
     */
    public static final Duration MEDIKIT_ABSORPTION_DURATION = Duration.ofSeconds(60);

    /**
     * Absorption's amplifier, derived from {@link HungerGamesSettings#medikitAbsorptionLevel()} the same way
     * {@link #MEDIKIT_REGENERATION_AMPLIFIER} is. Kept here as a documented fallback and reference value.
     */
    public static final int MEDIKIT_ABSORPTION_AMPLIFIER = 1;

    // ==================== lightning strike ====================

    /**
     * How many bolts one use calls down.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningBoltCount()}. Kept here at the source's own default.
     */
    public static final int LIGHTNING_BOLT_COUNT = 6;

    /**
     * How long between one bolt and the next.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningBoltDelay()}, in ticks, converted to milliseconds at the moment the
     * strike is called. Kept here at the source's own default of three ticks (150 milliseconds), staggered
     * rather than simultaneous so the strike reads as a storm arriving.
     */
    public static final Duration LIGHTNING_BOLT_DELAY = Duration.ofMillis(150);

    /**
     * How far from a bolt's landing point its damage, fire and knock-up reach, in blocks.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningDamageRadius()}.
     */
    public static final int LIGHTNING_DAMAGE_RADIUS = 4;

    /**
     * The extra damage each bolt deals, on top of whatever Bukkit's own lightning-strike damage would be.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningBonusDamage()}. The source's strikes were purely cosmetic
     * ({@code strikeLightningEffect}, no vanilla damage at all), so this is the <em>entire</em> damage a bolt
     * does, not a bonus on top of one.
     */
    public static final double LIGHTNING_BONUS_DAMAGE = 8.0;

    /**
     * How long a struck target is left burning.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningFireTicks()}, in ticks, converted to seconds. Kept here at the
     * source's own default of eighty ticks.
     */
    public static final Duration LIGHTNING_FIRE_DURATION = Duration.ofSeconds(4);

    /**
     * Whether a struck target is tossed upward.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#lightningKnockup()}. Kept here at the source's own default of {@code true}.
     */
    public static final boolean LIGHTNING_KNOCKUP = true;

    // ==================== krückauwasser ====================

    /**
     * How far the splash's nausea and blindness reach once it lands, in blocks.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#krueckauRadius()}. Kept here at the source's own field default of four —
     * smaller than the smoke bomb's, because this item is thrown rather than triggered on the holder.
     */
    public static final double KRUECKAUWASSER_RADIUS = 4.0;

    /**
     * How long the nausea lasts.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#krueckauNauseaSeconds()}. Kept here at the source's own field default of
     * eight seconds, the one the source's own class javadoc and lore text ("starke Übelkeit") were written
     * against, rather than its differing runtime config default of twelve.
     */
    public static final Duration KRUECKAUWASSER_NAUSEA_DURATION = Duration.ofSeconds(8);

    /**
     * How long the blindness lasts.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#krueckauBlindnessSeconds()}. Kept here at the source's own field default of
     * four seconds, matching its lore's promise of "+ leichte Blindheit" ("+ mild blindness") rather than the
     * runtime config default of zero, which would have made that promise false.
     */
    public static final Duration KRUECKAUWASSER_BLINDNESS_DURATION = Duration.ofSeconds(4);

    // ==================== aura of protection ====================

    /**
     * How long the aura stays up once activated.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#auraDurationSeconds()}.
     */
    public static final Duration AURA_DURATION = Duration.ofSeconds(5);

    /**
     * How far the aura reaches, in blocks.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#auraRadius()}.
     */
    public static final double AURA_RADIUS = 4.0;

    /**
     * How much damage each pulse deals to a caught enemy.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#auraDamage()}.
     */
    public static final double AURA_DAMAGE = 6.0;

    /**
     * How often the aura pulses while it is up.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#auraInterval()}, in ticks, converted to milliseconds. Kept here at the
     * source's own default of ten ticks (500 milliseconds).
     */
    public static final Duration AURA_PULSE_INTERVAL = Duration.ofMillis(500);

    /**
     * How hard each pulse shoves a caught enemy away.
     *
     * <p>Documented fallback and reference value only — the live value comes from
     * {@link HungerGamesSettings#auraKnockbackStrength()}, which already divides the stored tenths back into
     * an actual multiplier. Kept here at the source's own default of 0.6 (stored as the integer 6).
     */
    public static final double AURA_KNOCKBACK = 0.6;

    /** Fogging enemies nearby and hiding the thrower. Injected — it needs a world and a whole server around it. */
    @FunctionalInterface
    public interface Smokescreen {

        /** @return whether the bomb actually went off */
        boolean detonate(ItemUse use, double radius, Duration enemyEffectDuration, Duration invisibilityDuration);
    }

    /**
     * Healing the holder. Injected, because it touches a player's health and this class must not need one.
     *
     * <p>{@code windUp} is the medikit's cast time — {@code items.medikit.countdown-seconds}, three by
     * default. Zero heals at once. Anything else starts a treatment that lands later and is cancelled by any
     * damage in between, which is the whole price of the most valuable item in the sponsor shop: with it,
     * using one mid-fight is a gamble, and the counterplay to somebody using one is to keep hitting them.
     */
    @FunctionalInterface
    public interface Medicine {

        /**
         * @return whether the holder was healed <em>now</em>. False when a wind-up was started instead — the
         *         medikit is not spent yet, exactly as the source had it, so an interrupted treatment costs
         *         nothing and the item is still in the inventory to try again with
         */
        boolean treat(ItemUse use, Duration windUp, Duration regenerationDuration, int regenerationAmplifier,
                      Duration absorptionDuration, int absorptionAmplifier);
    }

    /** Calling down a staggered volley of lightning on whatever the holder is looking at. */
    @FunctionalInterface
    public interface Storm {

        /** @return whether there was anywhere to strike */
        boolean callDown(ItemUse use, int bolts, Duration boltDelay, int damageRadius, double bonusDamage,
                         Duration fireDuration, boolean knockUp);
    }

    /** Throwing (and landing) a bottle of krückauwasser. */
    @FunctionalInterface
    public interface Splash {

        /** @return whether the bottle actually landed on somebody */
        boolean drench(ItemUse use, double radius, Duration nauseaDuration, Duration blindnessDuration);
    }

    /** Raising the aura of protection around the holder for a while. */
    @FunctionalInterface
    public interface Aura {

        /** @return whether the aura actually went up */
        boolean protect(ItemUse use, Duration duration, double radius, double damage, Duration pulseInterval,
                        double knockback);
    }

    private final ItemAbilities abilities;
    private final CustomItems items;
    private final Supplier<GamePhase> phase;
    private final Smokescreen smokescreen;
    private final Medicine medicine;
    private final Storm storm;
    private final Splash splash;
    private final Aura aura;

    private volatile HungerGamesSettings settings;

    public CombatItemService(ItemAbilities abilities, CustomItems items, Supplier<GamePhase> phase,
                             Smokescreen smokescreen, Medicine medicine, Storm storm, Splash splash, Aura aura,
                             HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
        this.phase = phase;
        this.smokescreen = smokescreen;
        this.medicine = medicine;
        this.storm = storm;
        this.splash = splash;
        this.aura = aura;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Tells Core about all five items and all five abilities.
     *
     * <p>{@code defineIfAbsent} rather than {@code define}, for the item — a server owner may have edited a
     * name, a lore line or the model data, and overwriting that on every boot would make their edit look like
     * it never saved. The <em>ability</em> is registered outright, because that is code rather than
     * configuration and a stale one is a button that keeps doing last release's thing.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, SMOKE_BOMB)
                .material(Material.GUNPOWDER)
                .name("<dark_gray>Smoke Bomb")
                .lore(List.of(
                        "<gray>Fogs nearby enemies (blindness",
                        "<gray>+ slowness) and makes you fully",
                        "<gray>invisible, armour included, for a moment."))
                .glowing(true)
                .ability(SMOKE_BOMB)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, MEDIKIT)
                .material(Material.GLISTERING_MELON_SLICE)
                .name("<red>Medikit")
                .lore(List.of(
                        "<gray>A full heal, Regeneration and",
                        "<gray>extra hearts — after a short",
                        "<gray>wind-up. Taking damage cancels it.",
                        "<dark_gray>The sponsors' own rescue anchor."))
                .glowing(true)
                .ability(MEDIKIT)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, LIGHTNING_STRIKE)
                .material(Material.LIGHTNING_ROD)
                .name("<aqua>Lightning Strike")
                .lore(List.of(
                        "<gray>Calls a storm on whatever you",
                        "<gray>are looking at: several strikes,",
                        "<gray>area damage, fire and a knock-up."))
                .glowing(true)
                .ability(LIGHTNING_STRIKE)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, KRUECKAUWASSER)
                .material(Material.SPLASH_POTION)
                .name("<dark_green>Krückauwasser")
                .lore(List.of(
                        "<gray>Throw it at enemies:",
                        "<gray>strong nausea + mild blindness.",
                        "<dark_gray>Murky water from the Krückau."))
                .glowing(true)
                .ability(KRUECKAUWASSER)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, AURA_OF_PROTECTION)
                .material(Material.SHIELD)
                .name("<gold>Aura of Protection")
                .lore(List.of(
                        "<gray>Raises a protective aura for a",
                        "<gray>few seconds: enemies in range are",
                        "<gray>struck and pushed back automatically."))
                .glowing(true)
                .ability(AURA_OF_PROTECTION)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, SMOKE_BOMB)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Fogs nearby enemies and hides the thrower")
                .consumesItem()
                .attempts(this::throwSmokeBomb)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, MEDIKIT)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Heals the holder and grants extra hearts")
                .consumesItem()
                .attempts(this::useMedikit)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, LIGHTNING_STRIKE)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Calls down a volley of lightning")
                .consumesItem()
                .attempts(this::callLightning)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, KRUECKAUWASSER)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Throws a bottle of krückauwasser")
                .consumesItem()
                .attempts(this::throwKrueckauwasser)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, AURA_OF_PROTECTION)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Raises a protective aura around the holder")
                .consumesItem()
                .attempts(this::activateAura)
                .build());
    }

    // ==================== what the items do ====================

    /**
     * @return whether the bomb actually went off — {@code false} costs the holder no charge, which is the
     *         whole reason this is registered through {@code attempts(...)} rather than {@code does(...)}: a
     *         smoke bomb used between rounds must not vanish from the holder's inventory for nothing.
     */
    boolean throwSmokeBomb(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        HungerGamesSettings current = settings;
        return smokescreen.detonate(use, current.smokeBombRadius(),
                Duration.ofSeconds(current.smokeBombEnemyDuration()),
                Duration.ofSeconds(current.smokeBombInvisSeconds()));
    }

    /**
     * @return whether the holder was healed now; {@code false} does not take the medikit — which covers both
     *         "there was nobody to heal" and "a wind-up started", the second being the ordinary case on a
     *         server that has left {@code items.medikit.countdown-seconds} at its default
     */
    boolean useMedikit(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        HungerGamesSettings current = settings;
        return medicine.treat(use, Duration.ofSeconds(Math.max(0, current.medikitCountdownSeconds())),
                Duration.ofSeconds(current.medikitRegenSeconds()),
                current.medikitRegenLevel() - 1,
                Duration.ofSeconds(current.medikitAbsorptionSeconds()),
                current.medikitAbsorptionLevel() - 1);
    }

    /** @return whether there was anywhere to strike; {@code false} does not spend the item's charge. */
    boolean callLightning(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        HungerGamesSettings current = settings;
        return storm.callDown(use, current.lightningBoltCount(),
                Duration.ofMillis(current.lightningBoltDelay() * 50L), current.lightningDamageRadius(),
                current.lightningBonusDamage(), Duration.ofSeconds(current.lightningFireTicks() / 20L),
                current.lightningKnockup());
    }

    /** @return whether the bottle actually landed on somebody; {@code false} does not spend the item's charge. */
    boolean throwKrueckauwasser(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        HungerGamesSettings current = settings;
        return splash.drench(use, current.krueckauRadius(),
                Duration.ofSeconds(current.krueckauNauseaSeconds()),
                Duration.ofSeconds(current.krueckauBlindnessSeconds()));
    }

    /** @return whether the aura actually went up; {@code false} does not spend the item's charge. */
    boolean activateAura(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        HungerGamesSettings current = settings;
        return aura.protect(use, Duration.ofSeconds(current.auraDurationSeconds()), current.auraRadius(),
                current.auraDamage(), Duration.ofMillis(current.auraInterval() * 50L),
                current.auraKnockbackStrength());
    }

    /**
     * Whether a round is actually on.
     *
     * <p>Asked per use rather than once at registration, and per item rather than shared with
     * {@link ArenaItemService} — see that class's note on why a compass (or, here, a storm) pointed at nobody
     * between rounds is worse than an item that briefly does nothing.
     */
    boolean duringARound() {
        return phase.get() == GamePhase.RUNNING;
    }

    @Override
    public String describe() {
        return "the five sponsor combat items, as abilities Core dispatches";
    }
}
