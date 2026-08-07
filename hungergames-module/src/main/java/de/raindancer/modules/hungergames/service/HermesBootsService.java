package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hermes' boots: worn equipment with a flight budget for the round, not a right-click ability.
 *
 * <h2>Why this is not a fourth {@code MobilityItemService} ability</h2>
 * Every other item in that class is the same shape: click it, Core spends a charge or starts a cooldown,
 * something happens once. Hermes' boots are actual golden boots — put them on in the boots slot, and while
 * you are wearing them you may fly, for a total of {@link HungerGamesSettings#hermesFlightSeconds()}
 * seconds across the whole round. There is no click to dispatch and no charge to spend; what there is
 * instead is a budget that only spends while the holder is actually airborne and flying, which is a
 * per-tick question about the world rather than an answer to "was this item used". That question, and the
 * granting and revoking of the flight itself, is {@code HungerGamesWiring}'s to ask, once a second, of
 * every online tribute — this class only holds the number.
 *
 * <h2>Why the budget is per player, not per pair of boots</h2>
 * Boots are worn, not consumed — a tribute can take them off and put them back on, or hand them to a
 * teammate, and the boots themselves are unchanged either way. What is spent is the <em>wearer's</em> time
 * in the air, so the budget is keyed on the player, granted the first time they are seen wearing the boots
 * each round, and never refilled by taking them off and putting them on again.
 *
 * <h2>Why nothing here resets on disconnect</h2>
 * A tribute who logs out mid-round and rejoins is still the same tribute with the same remaining seconds —
 * see {@code ConnectionListener}'s own note on why a disconnect changes nothing about round state. Only
 * {@link #resetForNewRound()} clears the map, the same moment every other per-round tally does.
 */
public final class HermesBootsService implements IHungerGamesService {

    /** Who this module's items belong to, in Core's registry. */
    public static final String PLUGIN = "hungergames";

    /** Worn equipment, not a triggered ability — see the class note. */
    public static final String HERMES_BOOTS = "hermes-boots";

    private final CustomItems items;

    /** Seconds of flight left this round, keyed by tribute. Absent means "never granted yet". */
    private final Map<UUID, Integer> remainingSeconds = new ConcurrentHashMap<>();

    private volatile HungerGamesSettings settings;

    public HermesBootsService(CustomItems items, HungerGamesSettings settings) {
        this.items = items;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * The resource pack's own number for the boots' texture override — {@code hgpack}'s
     * {@code assets/minecraft/items/golden_boots.json} dispatches on this exact value (threshold 1001),
     * to {@code assets/hgpack/models/item/hermes_boots.json}. Changing this without changing the pack in
     * lockstep silently puts a plain golden boot back on every tribute.
     */
    private static final int HERMES_BOOTS_MODEL_DATA = 1001;

    /**
     * Tells Core about the item.
     *
     * <p>{@code defineIfAbsent}, the same reasoning as every other item service: a server owner may have
     * re-skinned it in a menu, and booting over that edit would make the change look like it never saved.
     * No {@code .ability(...)} at all — there is nothing for a right click to dispatch to.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, HERMES_BOOTS)
                .material(Material.GOLDEN_BOOTS)
                .name("<yellow>Hermes' Boots")
                .lore(List.of(
                        "<gray>Wear them. While flying, they spend",
                        "<gray>" + settings.hermesFlightSeconds() + "s of flight for the round.",
                        "<dark_gray>Standing still costs nothing."))
                .glowing(true)
                .modelData(HERMES_BOOTS_MODEL_DATA)
                .build());
    }

    /** Grants the full budget, but only the first time this round — see the class note. */
    public void grantIfAbsent(UUID tribute) {
        remainingSeconds.putIfAbsent(tribute, Math.max(0, settings.hermesFlightSeconds()));
    }

    /** Seconds of flight left, or zero if never granted (never worn the boots this round). */
    public int remaining(UUID tribute) {
        return remainingSeconds.getOrDefault(tribute, 0);
    }

    /** Whether there is any budget left to fly on. */
    public boolean hasFlightLeft(UUID tribute) {
        return remaining(tribute) > 0;
    }

    /**
     * One second of actual flight spent.
     *
     * @return the remaining budget after spending it, floored at zero
     */
    public int depleteOneSecond(UUID tribute) {
        // merge's own contract: absent means the given value is stored as-is, with the remapping function
        // never called — so the value here is what an ungranted budget floors at (zero), not a delta.
        return remainingSeconds.merge(tribute, 0, (was, zero) -> Math.max(0, was - 1));
    }

    /** Whether the warning threshold has been crossed — the moment to start telling them, not before. */
    public boolean isRunningLow(UUID tribute) {
        int left = remaining(tribute);
        return left > 0 && left <= settings.hermesWarningSeconds();
    }

    /** A fresh round, a fresh budget for everybody — the same moment every other per-round tally resets. */
    public void resetForNewRound() {
        remainingSeconds.clear();
    }

    @Override
    public String describe() {
        return "Hermes' boots: a worn flight budget for the round";
    }
}
