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
 * The two arena items, registered with RainsCore rather than implemented here.
 *
 * <h2>Why there is no ItemListener</h2>
 * The plugin this is ported from had one, and behind it a 1120-line {@code CustomItems} class that built item
 * stacks, kept a cooldown map per player per item, counted charges, watched
 * {@code PlayerInteractEvent} to spot a right-click, and remembered to clear all of it when somebody left.
 *
 * <p>Every one of those is Core's: {@link CustomItems} defines the item, {@link ItemAbilities} owns the
 * cooldown and the charge count, {@link ItemTrigger} says what sets it off, and Core's own listener does the
 * watching. What is left for this module is the two things Core cannot know — <b>what the item does</b>, as a
 * predicate, and <b>that it only does it during a round</b>.
 *
 * <p>The saving is not only lines. Three of the things the old class did by hand are things it got wrong in
 * ways nobody would find by reading: a cooldown map with no eviction (an entry per player who ever held the
 * item), a charge count that survived a rejoin because it was keyed on the item stack rather than the player,
 * and an ability that fired on both {@code RIGHT_CLICK_AIR} and {@code RIGHT_CLICK_BLOCK} for one click.
 *
 * <h2>Why the phase check is in the predicate rather than around it</h2>
 * Because Core is what will call this, from its own listener, and the answer has to be current at the moment
 * of the click rather than at the moment of registration. A Fiendfinder pointing at the nearest tribute
 * between rounds is a compass that tells somebody where a player is standing on a server they are not playing
 * a game with — which is a considerably worse thing to ship than a broken item.
 */
public final class ArenaItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Points at the nearest living tribute. */
    public static final String FIENDFINDER = "fiendfinder";

    /** Makes the holder invisible for a while. */
    public static final String CLOAK = "invisibility-cloak";

    /**
     * How long the Fiendfinder waits between readings.
     *
     * <p>Fifteen seconds. Short enough to be worth carrying, long enough that it cannot be held down as a
     * live tracker — a compass that updates continuously removes hiding from the game entirely, which is half
     * of what the border exists to defeat.
     */
    public static final Duration FIENDFINDER_COOLDOWN = Duration.ofSeconds(15);

    /**
     * How many times a cloak may be used.
     *
     * <p>Three, and charges rather than a cooldown because the two mean different things here: a cooldown
     * makes the cloak a rhythm to be learned, and charges make it a resource to be spent at the right moment.
     * The second is the more interesting item, and it is what the source intended before its charge counter
     * turned out to reset on a rejoin.
     */
    public static final int CLOAK_CHARGES = 3;

    /** Making somebody invisible. Injected, because it touches a player and this class must not need one. */
    @FunctionalInterface
    public interface Cloaking {

        /** @return whether the holder actually became invisible */
        boolean hide(ItemUse use, Duration forHowLong);
    }

    /** Pointing a compass at the nearest living tribute. */
    @FunctionalInterface
    public interface Tracking {

        /** @return whether there was anybody to point at */
        boolean pointAtNearestTribute(ItemUse use);
    }

    private final ItemAbilities abilities;
    private final CustomItems items;
    private final Supplier<GamePhase> phase;
    private final Cloaking cloaking;
    private final Tracking tracking;

    private volatile HungerGamesSettings settings;

    public ArenaItemService(ItemAbilities abilities, CustomItems items, Supplier<GamePhase> phase,
                            Cloaking cloaking, Tracking tracking, HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
        this.phase = phase;
        this.cloaking = cloaking;
        this.tracking = tracking;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Tells Core about both items and both abilities.
     *
     * <p>{@code defineIfAbsent} rather than {@code define}: a server owner may have edited the Fiendfinder's
     * name or its model data, and overwriting that on every boot would make their change look like it never
     * saved. The <em>ability</em> is registered outright, because that is code rather than configuration and
     * a stale one is a button that does last release's thing.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, FIENDFINDER)
                // A spyglass, as it always was (Fiendfinder.java:45) — and the source refused to work on any
                // other material at all (:87), so this was load-bearing rather than decorative. The port had
                // made it a compass: defensible in isolation, and nobody asked for it. It is the item people
                // recognise in a hotbar during a fight.
                .material(Material.SPYGLASS)
                .name("<light_purple>Fiendfinder")
                .lore(List.of(
                        "<gray>Points at the nearest living tribute.",
                        "<dark_gray>Right-click. " + FIENDFINDER_COOLDOWN.toSeconds()
                                + "s between readings."))
                .glowing(true)
                .ability(FIENDFINDER)
                .build());

        items.defineIfAbsent(CustomItem.builder(PLUGIN, CLOAK)
                .material(Material.PHANTOM_MEMBRANE)
                .name("<dark_purple>Invisibility Cloak")
                .lore(List.of(
                        "<gray>Disappear for a moment.",
                        "<dark_gray>Right-click. " + CLOAK_CHARGES + " uses."))
                .glowing(true)
                .ability(CLOAK)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, FIENDFINDER)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Points at the nearest living tribute")
                .cooldown(FIENDFINDER_COOLDOWN)
                .attempts(this::readTheFiendfinder)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, CLOAK)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Disappear for a moment")
                .charges(CLOAK_CHARGES)
                .attempts(this::wearTheCloak)
                .build());
    }

    // ==================== what the items do ====================

    /**
     * @return whether the reading happened — {@code false} costs the holder no cooldown, which is the whole
     *         reason this is registered through {@code attempts(...)} rather than {@code does(...)}: Core
     *         only spends the cooldown when the predicate says the use succeeded. A compass used with nobody
     *         left to find must not burn fifteen seconds.
     */
    boolean readTheFiendfinder(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return tracking.pointAtNearestTribute(use);
    }

    /** @return whether the holder actually vanished; {@code false} does not spend a charge. */
    boolean wearTheCloak(ItemUse use) {
        if (!duringARound()) {
            return false;
        }
        return cloaking.hide(use, Duration.ofSeconds(settings.gracePeriodSeconds()));
    }

    /**
     * Whether a round is actually on.
     *
     * <p>Asked per use rather than once at registration. An arena item that works between rounds is a tracker
     * pointed at people who are not playing — see the class note.
     */
    boolean duringARound() {
        return phase.get() == GamePhase.RUNNING;
    }

    @Override
    public String describe() {
        return "the arena's own items, as abilities Core dispatches";
    }
}
