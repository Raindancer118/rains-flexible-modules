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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Four of the source plugin's custom items, ported onto Core's {@link ItemAbilities} the same way
 * {@link ArenaItemService} ports the other two — see that class's javadoc for why there is no
 * {@code ItemListener} here at all.
 *
 * <h2>Feast and War Kit</h2>
 * Straightforward right-click grants: {@link #FEAST} refills hunger and hands out a short regeneration and
 * some golden apples, {@link #WAR_KIT} puts a full armour set on the holder. Neither carries a cooldown or a
 * charge limit, which is not an oversight — the source plugin never throttled either of them either. Both
 * are single sponsor purchases or crafts; the cost of getting one is the only rationing that was ever
 * designed in, and adding an ability-level limit on top of that would be inventing a second currency nobody
 * asked for.
 *
 * <h2>Stupidness Protector — passive, and honestly so</h2>
 * The source plugin's "Trottel-Schutz" is a totem against dying stupidly: it saves its holder once from
 * lethal <em>environmental</em> damage — lava, a fall, fire, drowning, a stray mob — but never from another
 * tribute's blade, and never from a custom weapon like {@link #EXMATRIKULATOR}. {@link ItemTrigger} does
 * carry a {@code LETHAL_DAMAGE} case that reads, at first glance, like the obvious fit. It is not: an
 * {@link ItemUse} says who used an item and what ability fired, and nothing at all about <em>what hurt
 * them</em> — a right hook from another tribute and a faceful of lava are the same event as far as that
 * record is concerned. Wiring this item through {@code LETHAL_DAMAGE} would either save people from
 * everything, including the one case the source plugin was explicit about excluding, or require guessing
 * the cause from context this class has no honest way to obtain. So instead of forcing that trigger to say
 * something it cannot say, this item is registered with Core (so it can be crafted, shown in a shop, and
 * recognised in an inventory) but has no ability behind it at all. {@link #wouldSaveFrom} is the real
 * surface: a plain method taking the holder and a cause name, for whichever listener ends up watching
 * {@code EntityDamageEvent} to call once it already knows, from the event itself, whether the damage was a
 * player's doing.
 *
 * <h2>Exmatrikulator — an aura, not a single strike</h2>
 * The source item opens a several-second window of repeated lightning volleys rather than firing once, which
 * is a repeating timer — exactly the shape {@code GameTimerService} and {@code SupplyDropService} already
 * give a shape to in this module: state advanced by an externally driven {@link #pulse()} rather than a
 * {@code BukkitRunnable} this class would have to own and cancel correctly on every exit path. Activating the
 * item only records that an aura has begun; {@link #pulse()}, called on whatever cadence the module already
 * calls its other services on, is what actually asks {@link Volley} to strike. A hit is remembered against
 * the victim's {@link UUID} for a short window, so a death inside that window can be reported as an
 * exmatrikulation rather than a plain kill — the source plugin's own death-message flourish — without this
 * class ever needing to resolve a name itself: {@link #exmatrikulationPhrase} takes the killer's name from
 * whoever is asking, at the moment they are asking, because only a real listener holding a real
 * {@code Player} can honestly know it.
 */
public final class SurvivalItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Refills hunger, grants a short regeneration and hands out golden apples. */
    public static final String FEAST = "feast";

    /** Puts a full set of armour on the holder. */
    public static final String WAR_KIT = "war-kit";

    /** Saves its holder once from lethal environmental damage. Passive — see the class javadoc. */
    public static final String STUPIDNESS_PROTECTOR = "stupidness-protector";

    /** A several-second aura of lightning volleys aimed at whatever is nearby. */
    public static final String EXMATRIKULATOR = "exmatrikulator";

    // ==================== feast ====================

    /**
     * How long the feast's regeneration lasts.
     *
     * <p>Five seconds — the source plugin's own number. Long enough to top up whatever health the golden
     * apples and the full food bar do not, short enough that a feast is a meal rather than a standing buff.
     */
    public static final Duration FEAST_REGENERATION = Duration.ofSeconds(5);

    /** Regeneration II, matching the source plugin's amplifier of 1 (amplifier is level minus one). */
    public static final int FEAST_REGENERATION_LEVEL = 2;

    /** How many golden apples a feast hands out, on top of the regeneration — the source default. */
    public static final int FEAST_GOLDEN_APPLES = 2;

    /** Feeding somebody a feast. Injected, because it touches a player's food bar and inventory. */
    @FunctionalInterface
    public interface Feasting {

        /** @return whether there was a live holder to feed */
        boolean feed(ItemUse use, Duration regeneration, int regenerationLevel, int goldenApples);
    }

    // ==================== war kit ====================

    /**
     * The armour a War Kit puts on. Iron, matching the source plugin's own default tier — the source made
     * this a config string ({@code items.warkit.material}), which this port has no settings key for; iron is
     * what a server running unmodified defaults actually got.
     */
    public static final List<Material> WAR_KIT_ARMOUR = List.of(
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);

    /** Putting armour on somebody. A {@link List} of pieces rather than four separate calls, so an
     *  implementation can decide once whether each slot is already occupied. */
    @FunctionalInterface
    public interface Armoury {

        /** @return whether the holder actually received the armour */
        boolean equip(ItemUse use, List<Material> pieces);
    }

    // ==================== stupidness protector ====================

    /**
     * Damage causes this item refuses to save anyone from — its whole reason for existing as a passive
     * rescue rather than an unconditional one. {@code "PLAYER"} is another tribute's kill; {@code
     * "CUSTOM_ITEM"} is a weapon like {@link #EXMATRIKULATOR}'s lightning. Everything else — lava, a fall,
     * fire, drowning, an ordinary mob — is the "environmental pech" the source plugin's own listener let
     * through, by checking the damager's type before ever asking whether to save anyone. Comparison is
     * case-insensitive, so whichever spelling a listener's own cause enum happens to use still matches.
     */
    public static final Set<String> STUPIDNESS_EXCLUDED_CAUSES = Set.of("PLAYER", "CUSTOM_ITEM");

    /** How long the regeneration granted by a successful rescue lasts — the source default. */
    public static final Duration STUPIDNESS_REGENERATION = Duration.ofSeconds(8);

    /** How long the fire resistance granted by a successful rescue lasts — the source default. */
    public static final Duration STUPIDNESS_FIRE_RESISTANCE = Duration.ofSeconds(10);

    /** How far the protective shove reaches, in blocks — the source default. */
    public static final double STUPIDNESS_SHOVE_RADIUS = 5.0;

    /** How hard the protective shove pushes — the source default (its own {@code 12} tenths). */
    public static final double STUPIDNESS_SHOVE_STRENGTH = 1.2;

    /**
     * Consuming a protector and actually saving somebody: a heal, the regeneration and fire resistance
     * above, and a shove that clears whatever was about to finish them off.
     */
    @FunctionalInterface
    public interface Rescue {

        /** @return whether the holder actually had a protector to consume */
        boolean save(UUID holder, Duration regeneration, Duration fireResistance, double shoveRadius,
                     double shoveStrength);
    }

    // ==================== exmatrikulator ====================

    /** How long one activation's aura lasts — the source default. */
    public static final Duration EXMATRIKULATOR_DURATION = Duration.ofSeconds(5);

    /** How often, while the aura is up, it fires another volley — the source default of four ticks. */
    public static final Duration EXMATRIKULATOR_INTERVAL = Duration.ofMillis(200);

    /** How far a volley reaches, in blocks — the source default. */
    public static final double EXMATRIKULATOR_RADIUS = 8.0;

    /** The most targets one volley strikes, so a crowd does not turn one item into a wipe — source default. */
    public static final int EXMATRIKULATOR_MAX_TARGETS = 5;

    /** The bonus damage each bolt in a volley deals — the source default. */
    public static final double EXMATRIKULATOR_DAMAGE = 6.0;

    /** How long a volley sets its targets alight for — the source default of forty ticks. */
    public static final Duration EXMATRIKULATOR_FIRE_DURATION = Duration.ofSeconds(2);

    /**
     * How long after being struck a death still counts as an exmatrikulation — the source default of four
     * seconds. Long enough that a target who runs off burning and dies to the fire a moment later still gets
     * the flourish; short enough that a death an hour later, coincidentally on the same server, does not.
     */
    public static final Duration EXMATRIKULATOR_KILL_WINDOW = Duration.ofSeconds(4);

    /**
     * The placeholder a death-message template carries for whoever fired the exmatrikulator.
     *
     * <p>The source's own spelling, and it is not decoration: an upgrading server's own
     * {@code items.exmatrikulator.death-messages} are written with these, so changing either name would
     * print the placeholder to the server, mid-sentence, in front of everybody.
     */
    public static final String KILLER_PLACEHOLDER = "%killer%";

    /** The placeholder for the module somebody failed. The source's spelling — German, deliberately. */
    public static final String MODULE_PLACEHOLDER = "%modul%";

    /**
     * The fallback module name, for a server that emptied {@code items.exmatrikulator.modules}.
     *
     * <p>The source's own: an empty list there means "no module in particular", not "no death message".
     */
    public static final String NO_PARTICULAR_MODULE = "einem Wahlpflichtmodul";

    /**
     * Striking whatever is near the holder with one volley of the aura.
     *
     * @see #pulse()
     */
    @FunctionalInterface
    public interface Volley {

        /**
         * @return the living targets actually struck, players among them included — a struck player is
         *         remembered for {@link #EXMATRIKULATOR_KILL_WINDOW}, so a death of theirs shortly after
         *         reads as an exmatrikulation
         */
        List<UUID> strike(UUID holder, double radius, int maxTargets, double damage, Duration fireDuration);
    }

    /** One activation's aura: when it ends, and when its next volley is due. */
    private record ActiveAura(long endsAtMillis, long nextVolleyAtMillis) {

        ActiveAura withNextVolleyAt(long nextVolleyAtMillis) {
            return new ActiveAura(endsAtMillis, nextVolleyAtMillis);
        }
    }

    /** A struck player's remembered exmatrikulation, expiring after {@link #EXMATRIKULATOR_KILL_WINDOW}. */
    private record ExmatrikulationHit(long expiresAtMillis, String templateWithModule) {
    }

    private final ItemAbilities abilities;
    private final CustomItems items;
    private final Supplier<GamePhase> phase;
    private final Feasting feasting;
    private final Armoury armoury;
    private final Rescue rescue;
    private final Volley volley;
    private final LongSupplier clock;
    private final Random random;

    /** Every aura currently up, keyed by whoever raised it. */
    private final Map<UUID, ActiveAura> activeAuras = new ConcurrentHashMap<>();

    /** Every recent exmatrikulation hit still inside its kill window, keyed by the victim. */
    private final Map<UUID, ExmatrikulationHit> exmatrikulationHits = new ConcurrentHashMap<>();

    private volatile HungerGamesSettings settings;

    public SurvivalItemService(ItemAbilities abilities, CustomItems items, Supplier<GamePhase> phase,
                               Feasting feasting, Armoury armoury, Rescue rescue, Volley volley,
                               LongSupplier clock, Random random, HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
        this.phase = phase;
        this.feasting = feasting;
        this.armoury = armoury;
        this.rescue = rescue;
        this.volley = volley;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Tells Core about all four items and the three abilities behind them. {@code defineIfAbsent}, not
     * {@code define} — see {@link ArenaItemService#register()}'s javadoc for why an item definition must
     * not silently overwrite a server owner's own edit to it on every boot.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, FEAST)
                .material(Material.COOKED_BEEF)
                .name("<gold>Capitol Feast")
                .lore(List.of(
                        "<gray>Fills your hunger and saturation,",
                        "<gray>grants a short regeneration,",
                        "<gray>and hands you golden apples.",
                        "<dark_gray>Right-click. A gift from the sponsors."))
                .glowing(true)
                .ability(FEAST)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, WAR_KIT)
                .material(Material.IRON_CHESTPLATE)
                .name("<gray>War Kit")
                .lore(List.of(
                        "<gray>A full set of armour,",
                        "<gray>equipped on the spot.",
                        "<dark_gray>Right-click. Instant protection."))
                .glowing(true)
                .ability(WAR_KIT)
                .build());

        // No .ability(...) here — see the class javadoc for why this item is passive rather than a
        // right-click ability.
        items.defineIfAbsent(CustomItem.builder(PLUGIN, STUPIDNESS_PROTECTOR)
                .material(Material.NAUTILUS_SHELL)
                .name("<dark_aqua>Stupidness Protector")
                .lore(List.of(
                        "<gray>Passive: saves you once from lethal",
                        "<gray>environmental damage — lava, a fall,",
                        "<gray>fire, an ordinary mob.",
                        "<dark_gray>Does NOT save you from another tribute."))
                .glowing(true)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, EXMATRIKULATOR)
                .material(Material.BREEZE_ROD)
                .name("<light_purple>Exmatrikulator")
                .lore(List.of(
                        "<gray>Unleashes a lightning aura for a",
                        "<gray>few seconds, striking everything",
                        "<gray>nearby in repeated volleys.",
                        "<dark_gray>Right-click. Expensive to craft."))
                .glowing(true)
                .ability(EXMATRIKULATOR)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, FEAST)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Fills hunger, grants regeneration and golden apples")
                .consumesItem()
                .attempts(this::useFeast)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, WAR_KIT)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Equips a full set of armour")
                .consumesItem()
                .attempts(this::useWarKit)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, EXMATRIKULATOR)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Unleashes a several-second lightning aura")
                .consumesItem()
                .attempts(this::useExmatrikulator)
                .build());
    }

    // ==================== what the items do ====================

    /** @return whether there was a live holder to feed — always the case during a round. */
    boolean useFeast(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return feasting.feed(use, FEAST_REGENERATION, FEAST_REGENERATION_LEVEL, FEAST_GOLDEN_APPLES);
    }

    /** @return whether the holder actually received the armour. */
    boolean useWarKit(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return armoury.equip(use, WAR_KIT_ARMOUR);
    }

    /**
     * Begins the aura. Recording state rather than striking anything itself — the striking is
     * {@link #pulse()}'s job, on whatever cadence something outside this class already drives its other
     * timers on.
     *
     * @return whether the aura was actually raised
     */
    boolean useExmatrikulator(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        long now = clock.getAsLong();
        activeAuras.put(use.player(),
                new ActiveAura(now + EXMATRIKULATOR_DURATION.toMillis(), now));
        return true;
    }

    /**
     * Whether a given cause of death should be survived, and — if so — actually saves the holder.
     *
     * <p>Not registered as an ability: see the class javadoc for why {@link ItemTrigger#LETHAL_DAMAGE} is
     * the wrong fit for a rescue that must not fire against another tribute's kill. Whoever is watching
     * {@code EntityDamageEvent} calls this once they already know the damage would be lethal and what
     * caused it.
     *
     * @param causeName the damage cause, in whatever spelling the caller's own cause enum uses; compared
     *                   case-insensitively against {@link #STUPIDNESS_EXCLUDED_CAUSES}
     * @return whether the holder was saved — the caller should cancel the damage exactly when this is true
     */
    public boolean wouldSaveFrom(UUID holder, String causeName) {
        if (!duringARound()) {
            return false;
        }
        if (causeName != null && STUPIDNESS_EXCLUDED_CAUSES.contains(causeName.toUpperCase(java.util.Locale.ROOT))) {
            return false;
        }
        return rescue.save(holder, STUPIDNESS_REGENERATION, STUPIDNESS_FIRE_RESISTANCE,
                STUPIDNESS_SHOVE_RADIUS, STUPIDNESS_SHOVE_STRENGTH);
    }

    /**
     * Advances every aura currently up: strikes a volley wherever one is due, and drops any aura whose
     * duration has run out. Meant to be called on the same cadence something outside this class already
     * calls its other repeating services on — see the class javadoc.
     */
    public void pulse() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<UUID, ActiveAura>> it = activeAuras.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveAura> entry = it.next();
            ActiveAura aura = entry.getValue();
            if (now >= aura.endsAtMillis()) {
                it.remove();
                continue;
            }
            if (now < aura.nextVolleyAtMillis()) {
                continue;
            }
            UUID holder = entry.getKey();
            List<UUID> struck = volley.strike(holder, EXMATRIKULATOR_RADIUS, EXMATRIKULATOR_MAX_TARGETS,
                    EXMATRIKULATOR_DAMAGE, EXMATRIKULATOR_FIRE_DURATION);
            for (UUID victim : struck) {
                markExmatrikuliert(victim, now);
            }
            entry.setValue(aura.withNextVolleyAt(now + EXMATRIKULATOR_INTERVAL.toMillis()));
        }
    }

    /**
     * Picks a random template and module, and remembers the result against the victim for the kill window.
     *
     * <p>Both lists come from the settings — {@code items.exmatrikulator.death-messages} and
     * {@code items.exmatrikulator.modules}, at the paths the source used. They were written out here as a
     * fixed English set at first, which quietly threw away every line a server had written: the live server
     * had nine module names and five templates of its own, and none of them would ever have appeared again.
     */
    private void markExmatrikuliert(UUID victim, long now) {
        HungerGamesSettings current = settings;
        List<String> templates = current.exmatrikulatorDeathMessages();
        List<String> modules = current.exmatrikulatorModules();
        if (templates.isEmpty()) {
            // An owner who emptied the list has switched the flourish off. The vanilla death message stands
            // rather than a made-up one that is not in their file.
            return;
        }
        String template = templates.get(random.nextInt(templates.size()));
        String module = modules.isEmpty()
                ? NO_PARTICULAR_MODULE : modules.get(random.nextInt(modules.size()));
        String withModule = template.replace(MODULE_PLACEHOLDER, module);
        exmatrikulationHits.put(victim,
                new ExmatrikulationHit(now + EXMATRIKULATOR_KILL_WINDOW.toMillis(), withModule));
    }

    /**
     * The exmatrikulation phrase for a victim struck within the kill window, with {@code killerName}
     * substituted in — taken from the caller rather than resolved here, because only whoever is handling the
     * actual death (with a real {@code Player} in hand) can honestly know it. See the class javadoc.
     *
     * @return the phrase to follow the victim's name with, if their death still falls inside the window
     */
    public Optional<String> exmatrikulationPhrase(UUID victim, String killerName) {
        ExmatrikulationHit hit = exmatrikulationHits.get(victim);
        if (hit == null) {
            return Optional.empty();
        }
        if (hit.expiresAtMillis() < clock.getAsLong()) {
            exmatrikulationHits.remove(victim);
            return Optional.empty();
        }
        return Optional.of(hit.templateWithModule().replace(KILLER_PLACEHOLDER, killerName));
    }

    /**
     * Whether a round is actually on. Asked per use rather than once at registration — see
     * {@link ArenaItemService#duringARound()}'s javadoc for why.
     */
    boolean duringARound() {
        return phase.get() == GamePhase.RUNNING;
    }

    @Override
    public String describe() {
        return "the feast, the war kit, the stupidness protector and the exmatrikulator";
    }
}
