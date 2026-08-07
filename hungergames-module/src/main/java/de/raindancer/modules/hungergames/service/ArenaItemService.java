package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Material;

import java.util.List;

/**
 * The arena's own item — the Fiendfinder — registered with RainsCore rather than implemented here.
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
 * <h2>Why there is no round check any more</h2>
 * There used to be one: a Fiendfinder that only worked while {@code GamePhase.RUNNING}. Real feedback from
 * using it — testing an item is the reason to hold one between rounds, and a gamemaster checking whether the
 * grappling hook still pulls should not have to start a whole tournament first. What tracking somebody
 * between rounds means is now the tracking function's own business, not this class's to refuse on its
 * behalf.
 */
public final class ArenaItemService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Points at the nearest living tribute. */
    public static final String FIENDFINDER = "fiendfinder";

    /**
     * The Fiendfinder is single use, exactly as the source had it — {@code Fiendfinder.activate} returns
     * whether to consume the item and nothing about a wait before the next one. An earlier pass of this
     * port added a fifteen-second cooldown on top of the source's own consumesItem() behaviour, which is
     * not a smaller version of the source's design, it is a different item: the source's Fiendfinder is
     * spent finding one enemy, once; this port's mistaken version could be right-clicked again after
     * fifteen seconds without spending a second one, which is not what "single use" means.
     */

    /** Pointing a compass at the nearest living tribute. */
    @FunctionalInterface
    public interface Tracking {

        /** @return whether there was anybody to point at */
        boolean pointAtNearestTribute(ItemUse use);
    }

    private final ItemAbilities abilities;
    private final CustomItems items;
    private final Tracking tracking;

    private volatile HungerGamesSettings settings;

    public ArenaItemService(ItemAbilities abilities, CustomItems items, Tracking tracking,
                            HungerGamesSettings settings) {
        this.abilities = abilities;
        this.items = items;
        this.tracking = tracking;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Tells Core about the item and its ability.
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
                        "<dark_gray>Right-click. Single use."))
                .glowing(true)
                .ability(FIENDFINDER)
                .build());

        abilities.register(ItemAbility.builder(PLUGIN, FIENDFINDER)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Points at the nearest living tribute")
                .consumesItem()
                .attempts(this::readTheFiendfinder)
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
        return tracking.pointAtNearestTribute(use);
    }

    @Override
    public String describe() {
        return "the Fiendfinder, as an ability Core dispatches";
    }
}
