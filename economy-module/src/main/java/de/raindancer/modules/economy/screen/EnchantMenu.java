package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.modules.economy.util.PermissionNodes;
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

import java.util.ArrayList;
import java.util.List;

/** The shop's Enchantments drawer: one book per enchantment and level, each with its live price; click to buy it. */
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
        YourPrice yours = services.shop().priceFor(viewer.getUniqueId(), offer.key());
        PriceTag tag = yours.shop();
        List<String> lore = new ArrayList<>();
        lore.add(tag.buyable() ? "<gray>Buy: " + PriceLines.amount(services.currency(), tag.buy(), yours.buy())
                : "<dark_gray>Not sold");
        lore.addAll(PriceLines.buyNotes(yours));
        lore.add(tag.sellable() ? "<gray>Sell: " + PriceLines.amount(services.currency(), tag.sell(), yours.sell())
                : "<dark_gray>Not bought");
        lore.addAll(PriceLines.sellNotes(services.currency(), services.shop().sellBreakdown(viewer.getUniqueId(), offer.key())));
        String trend = ShopItemsMenu.trend(services.shop().prices().multiplier(offer.key().name()));
        if (!trend.isEmpty()) {
            lore.add(trend);
        }
        lore.add("");
        if (tag.buyable()) {
            lore.add("<yellow>Click<gray> to buy the book");
        }
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            lore.add("<yellow>Right click<gray> to change its prices");
        }
        book.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class, meta -> {
            meta.addStoredEnchant(offer.enchantment(), offer.level(), false);
            meta.displayName(offer.enchantment().displayName(offer.level()).color(NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(EnchantMenu::line).toList());
        });
        return book;
    }

    @Override
    protected void onClick(ShopService.EnchantOffer offer, InventoryClickEvent event) {
        if (event.isRightClick() && viewer.hasPermission(PermissionNodes.ADMIN)) {
            ItemStack icon = new ItemStack(Material.ENCHANTED_BOOK);
            icon.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class,
                    meta -> meta.addStoredEnchant(offer.enchantment(), offer.level(), false));
            new PriceEditMenu(services, viewer, this, offer.key().name(), icon,
                    "Enchanted Book (" + offer.key().readable() + ")").open();
            return;
        }
        if (!services.shop().prices().tag(offer.key().name()).buyable()) {
            return;
        }
        services.shop().buyEnchant(viewer, offer,
                services.shop().priceFor(viewer.getUniqueId(), offer.key()).buyFor(1).orElse(null));
        refresh();
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("An enchantment as a book, to put on with an anvil.",
                "Priced like any item: what making it costs, times how useful it is,",
                "moving with supply and demand.");
    }

    @Override
    public String describe() {
        return "enchanted books for sale";
    }
}
