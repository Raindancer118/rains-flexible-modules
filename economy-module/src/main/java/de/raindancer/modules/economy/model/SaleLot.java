package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;
import org.bukkit.Material;

/**
 * Something in an inventory the shop would buy, and what it fetches.
 *
 * @param slot  the inventory slot of a one-of-a-kind item (enchanted, worn), or -1 for plain items, which
 *              sell by kind from wherever they are
 * @param count how many
 */
public record SaleLot(Material material, int slot, int count, Money total, String label) {

    public boolean plain() {
        return slot < 0;
    }
}
