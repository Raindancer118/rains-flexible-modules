package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.economy.model.Card;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** How a card looks on the table: face up with its rank as the stack size, or its blue back. */
final class CardIcons {

    private CardIcons() {
    }

    static ItemStack face(Card card) {
        ItemStack icon = Icons.of(card.suit().red() ? Material.PINK_DYE : Material.PAPER,
                (card.suit().red() ? "<red>" : "<white>") + "<bold>" + card.label(), "<gray>" + card.fullName());
        icon.setAmount(card.rank());
        return icon;
    }

    static ItemStack back() {
        return Icons.of(Material.LIGHT_BLUE_STAINED_GLASS, "<aqua>?", "<dark_gray>Face down");
    }
}
