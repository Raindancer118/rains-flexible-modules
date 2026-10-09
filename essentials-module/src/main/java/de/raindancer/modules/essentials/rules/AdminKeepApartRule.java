package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.Set;

/**
 * What somebody in admin mode may put items into: only windows that hand everything back when they close,
 * and their own (admin) ender chest. Anything that keeps an item, or trades it away, would carry admin
 * items out to the survival side — by name, so a window type added later is refused until it is listed.
 */
public final class AdminKeepApartRule extends AbstractRule<String> {

    private static final Set<String> HANDS_BACK = Set.of("CRAFTING", "CREATIVE", "PLAYER", "WORKBENCH",
            "ENDER_CHEST", "ANVIL", "ENCHANTING", "GRINDSTONE", "SMITHING", "STONECUTTER", "LOOM", "CARTOGRAPHY");

    private static final Set<String> TAKES_FROM_HAND = Set.of("JUKEBOX", "LECTERN", "DECORATED_POT", "CAMPFIRE",
            "SOUL_CAMPFIRE", "FLOWER_POT", "COMPOSTER", "VAULT", "CRAFTER");

    public AdminKeepApartRule() {
        super("admin items stay on the admin side: no storage, trading or display blocks");
    }

    /** @param inventoryType an {@code InventoryType} name */
    public boolean mayUseWindow(String inventoryType) {
        return HANDS_BACK.contains(inventoryType);
    }

    /** @param material the clicked block's material name */
    public boolean mayUseBlock(String material) {
        return !TAKES_FROM_HAND.contains(material) && !material.endsWith("SHELF");
    }

    @Override
    public Verdict judge(String inventoryType) {
        return mayUseWindow(inventoryType) ? Verdict.allowed() : Verdict.refused("essentials.admin.kept-apart");
    }
}
