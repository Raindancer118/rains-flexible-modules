package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PriceLines;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** The shop's Enchantments drawer: one book per enchantment and level, click to buy it. */
public final class EnchantMenu extends PaginatedMenu<ShopService.EnchantOffer> implements IEconomyScreen {

    private final EconomyServices services;
    private final List<ShopService.EnchantOffer> offers;

    EnchantMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.offers = services.shop().enchantOffers();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Enchantments");
    }

    @Override
    public String breadcrumb() {
        return "Enchantments";
    }

    @Override
    protected List<ShopService.EnchantOffer> entries() {
        return offers;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No enchantments for sale");
    }

    @Override
    protected ItemStack icon(ShopService.EnchantOffer offer) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class, meta -> {
            meta.addStoredEnchant(offer.enchantment(), offer.level(), false);
            meta.displayName(offer.enchantment().displayName(offer.level()).color(NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            var yours = services.shop().enchantPriceFor(viewer.getUniqueId(), offer);
            meta.lore(List.of(line("<gray>" + PriceLines.amount(services.currency(), offer.price(), yours.price())
                            + (yours.changed() ? " <dark_aqua>" + String.join(", ", yours.reasons()) + " discount: "
                            + -yours.percent() + "%" : "")),
                    line("<yellow>Click<gray> to buy the book")));
        });
        return book;
    }

    @Override
    protected void onClick(ShopService.EnchantOffer offer, InventoryClickEvent event) {
        services.shop().buyEnchant(viewer, offer);
        refresh();
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("An enchantment as a book, to put on with an anvil.", "Higher levels cost more; treasure double.");
    }

    @Override
    public String describe() {
        return "enchanted books for sale";
    }
}
