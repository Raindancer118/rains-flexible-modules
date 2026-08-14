package de.raindancer.modules.claims.extension;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * One button an outside module wants drawn on {@link ClaimMenu}, for whichever claim and viewer it was
 * asked about.
 *
 * @see ClaimMenuExtension
 */
public record ClaimMenuButton(ItemStack icon, Consumer<InventoryClickEvent> onClick) {
}
