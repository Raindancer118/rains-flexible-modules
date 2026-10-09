package de.raindancer.modules.invsnap;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.social.economy.Fees;
import org.bukkit.Material;

import java.util.List;

import java.time.Duration;

/**
 * What an owner can decide about inventory snapshots.
 *
 * <p>The record <em>is</em> the schema — see {@code MannequinSettings} for why, and why every
 * component has a {@code with…} rather than a positional constructor being the way to change one.
 */
@Settings(id = "invsnap", topics = {
        @Topic(path = "invsnap", title = "Inventory snapshots", icon = Material.CHEST),
})
public record InvSnapSettings(

        @In("invsnap") @Title("Snapshot interval") @Range(min = 30, max = 86400)
        @Describe("Seconds between one automatic snapshot of every online player's inventory and "
                + "the next. Five minutes by default.")
        @Key("snapshot.interval-seconds")
        int snapshotIntervalSeconds,

        @In("invsnap") @Title("Snapshots kept per player") @Range(min = 1, max = 500)
        @Describe("How many of a player's most recent snapshots are kept. The oldest is dropped "
                + "once a new one would push the count past this.")
        @Key("snapshot.retention-count")
        int retentionCount,

        @In("invsnap/insurance") @Title("Death insurance")
        @Describe("On: players can opt in with /insurance on. When an insured player dies they pay a "
                + "premium and keep their inventory; if they cannot pay, the death is a normal one. "
                + "Needs an economy once any price below is set.")
        @Key("insurance.enabled")
        boolean insuranceEnabled,

        @In("invsnap/insurance") @Title("Premium, percent of inventory value") @Range(min = 0, max = 100)
        @Describe("This percent of what the inventory is worth at death (armour and off hand "
                + "included; items without a shop price count nothing). 0 for none.")
        @Key("insurance.price-percent")
        double insurancePricePercent,

        @In("invsnap/insurance") @Title("Premium, flat")
        @Describe("Added to the percentage: money, such as 50 or 1.5k. 0 for none.")
        @Key("insurance.price-flat")
        String insurancePriceFlat,

        @In("invsnap/insurance") @Title("Premium, most")
        @Describe("The most one death can cost, such as 500. 0 for no cap.")
        @Key("insurance.most")
        String insuranceMost,

        @In("invsnap/insurance") @Title("Keep experience too")
        @Describe("On: an insured death also keeps the level. Off: only items are insured.")
        @Key("insurance.keep-xp")
        boolean insuranceKeepXp,

        @In("invsnap/insurance") @Title("Insured worlds")
        @Describe("World names where insurance applies, comma separated. Empty means every world. "
                + "Leave out worlds where another plugin manages inventories.")
        @Key("insurance.worlds")
        List<String> insuranceWorlds,

        @In("invsnap/item-insurance") @Title("Item insurance")
        @Describe("On: a player can insure one unstackable item (tool, weapon, armour, elytra...) they "
                + "hold. If an insured item is destroyed (despawn, lava, fire, the void) or its owner dies, it comes back to the owner. Needs a "
                + "price below, and an economy once one is set.")
        @Key("item-insurance.enabled")
        boolean itemInsuranceEnabled,

        @In("invsnap/item-insurance") @Title("Premium, percent of item value") @Range(min = 0, max = 1000)
        @Describe("This percent of what the item is worth (its shop price), charged when the policy is "
                + "taken and at every renewal. 0 for none.")
        @Key("item-insurance.price-percent")
        double itemInsurancePricePercent,

        @In("invsnap/item-insurance") @Title("Premium, flat")
        @Describe("Added to the percentage: money, such as 50 or 1.5k. 0 for none.")
        @Key("item-insurance.price-flat")
        String itemInsurancePriceFlat,

        @In("invsnap/item-insurance") @Title("Premium, least")
        @Describe("The least one premium can be, such as 25. 0 for no floor. With no percentage, no flat "
                + "and no floor there is no price and nothing can be insured.")
        @Key("item-insurance.least")
        String itemInsuranceLeast,

        @In("invsnap/item-insurance") @Title("Renewal, hours") @Range(min = 1, max = 8760)
        @Describe("Hours between premiums. A premium that cannot be paid ends the policy. 168 is a week.")
        @Key("item-insurance.every-hours")
        int itemInsuranceEveryHours,

        @In("invsnap/item-insurance") @Title("Items per player") @Range(min = 1, max = 50)
        @Describe("How many items one player can have insured at once.")
        @Key("item-insurance.most-items")
        int itemInsuranceMostItems,

        @In("invsnap/item-insurance") @Title("Deductible")
        @Describe("Charged each time an insured item is returned after its owner's death, such as 20. "
                + "If they cannot pay it, the item comes back anyway. 0 for none.")
        @Key("item-insurance.claim-fee")
        String itemInsuranceClaimFee) {

    /** Snapshot settings with insurance at its shipped (off) default. */
    public InvSnapSettings(int snapshotIntervalSeconds, int retentionCount) {
        this(snapshotIntervalSeconds, retentionCount, false, 0, "0", "0", false, List.of(),
                false, 0, "0", "0", 168, 3, "0");
    }

    public static final InvSnapSettings DEFAULTS =
            new InvSnapSettings(300, 24, false, 0, "0", "0", false, List.of(),
                    false, 0, "0", "0", 168, 3, "0");

    /** {@link #snapshotIntervalSeconds}, clamped and widened into a real {@link Duration}. */
    public Duration snapshotInterval() {
        return Duration.ofSeconds(Math.max(30, Math.min(86_400, snapshotIntervalSeconds)));
    }

    /** {@link #retentionCount}, clamped. */
    public int retentionCountClamped() {
        return Math.max(1, Math.min(500, retentionCount));
    }

    public InvSnapSettings withSnapshotIntervalSeconds(int seconds) {
        return new InvSnapSettings(seconds, retentionCount, insuranceEnabled, insurancePricePercent,
                insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withRetentionCount(int count) {
        return new InvSnapSettings(snapshotIntervalSeconds, count, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withInsuranceEnabled(boolean enabled) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, enabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withInsurancePrice(double percent, String flat, String most) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                percent, flat, most, insuranceKeepXp, insuranceWorlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withInsuranceKeepXp(boolean keep) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, keep, insuranceWorlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withInsuranceWorlds(List<String> worlds) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, worlds, itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withItemInsurance(boolean enabled) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds,
                enabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                itemInsuranceEveryHours, itemInsuranceMostItems, itemInsuranceClaimFee);
    }

    public InvSnapSettings withItemInsurancePrice(double percent, String flat, String least) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds,
                itemInsuranceEnabled, percent, flat, least, itemInsuranceEveryHours, itemInsuranceMostItems,
                itemInsuranceClaimFee);
    }

    public InvSnapSettings withItemInsuranceTerms(int everyHours, int mostItems, String claimFee) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds,
                itemInsuranceEnabled, itemInsurancePricePercent, itemInsurancePriceFlat, itemInsuranceLeast,
                everyHours, mostItems, claimFee);
    }

    /** {@link #itemInsuranceEveryHours}, clamped, as the time between two premiums. */
    public Duration itemInsuranceEvery() {
        return Duration.ofHours(Math.max(1, Math.min(8_760, itemInsuranceEveryHours)));
    }

    /** {@link #itemInsuranceMostItems}, clamped. */
    public int itemInsuranceMostItemsClamped() {
        return Math.max(1, Math.min(50, itemInsuranceMostItems));
    }

    /** Whether any premium is set at all; with none, an item cannot be sold a policy. */
    public boolean itemInsurancePriced() {
        return itemInsurancePricePercent > 0
                || Fees.amount(itemInsurancePriceFlat).isPositive()
                || Fees.amount(itemInsuranceLeast).isPositive();
    }

    /** Whether insurance covers a death in this world; an empty list means every world. */
    public boolean insuresWorld(String world) {
        if (insuranceWorlds == null || insuranceWorlds.isEmpty()) {
            return true;
        }
        return insuranceWorlds.stream().anyMatch(name -> name.equalsIgnoreCase(world));
    }
}
