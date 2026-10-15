package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.PotionKey;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.util.PermissionNodes;
import de.raindancer.modules.economy.util.PriceLines;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shop's Potions drawer: every potion type as a drinkable, a splash and a lingering potion, and the ominous
 * bottles, each as the real item with its live price; click to buy one.
 */
public final class PotionMenu extends PaginatedMenu<ShopService.PotionOffer> implements IEconomyScreen {

    /** What shift click buys: a handful, since a potion does not stack. */
    static final int HANDFUL = 5;
    /** Arrows come by the eight a crafting makes, and by the stack. */
    static final int ARROWS = 8;
    static final int ARROW_STACK = 64;

    private final EconomyServices services;
    private final List<ShopService.PotionOffer> offers;
    /** What each bottle's icon last showed this player for one: buying charges exactly that, or refuses. */
    private final Map<PotionKey, Money> shown = new HashMap<>();

    PotionMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.offers = services.shop().potionOffers();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Potions");
    }

    @Override
    public String breadcrumb() {
        return "Potions";
    }

    @Override
    protected List<ShopService.PotionOffer> entries() {
        return offers;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.GLASS_BOTTLE, "<gray>No potions for sale");
    }

    @Override
    protected ItemStack icon(ShopService.PotionOffer offer) {
        PotionKey key = offer.key();
        Optional<ItemStack> made = ShopService.stackOf(key);
        if (made.isEmpty()) {
            return Icons.of(Material.BARRIER, "<red>" + key.readable());
        }
        ItemStack bottle = made.get();
        YourPrice yours = services.shop().priceFor(viewer.getUniqueId(), key);
        PriceTag tag = yours.shop();
        List<String> lore = new ArrayList<>();
        Optional<Money> buy = tag.buyable() ? yours.buyFor(1) : Optional.empty();
        Optional<Money> sell = tag.sellable() ? yours.sellFor(1) : Optional.empty();
        buy.ifPresentOrElse(price -> shown.put(key, price), () -> shown.remove(key));
        lore.add(buy.map(price -> "<gray>Buy: " + PriceLines.amount(services.currency(), tag.buy(), price))
                .orElse("<dark_gray>Not sold"));
        lore.addAll(PriceLines.buyNotes(yours));
        lore.add(sell.map(price -> "<gray>Sell: " + PriceLines.amount(services.currency(), tag.sell(), price))
                .orElse("<dark_gray>Not bought"));
        lore.addAll(PriceLines.sellNotes(services.currency(), services.shop().sellBreakdown(viewer.getUniqueId(), key)));
        String trend = ShopItemsMenu.trend(services.shop().prices().multiplier(key.name()));
        if (!trend.isEmpty()) {
            lore.add(trend);
        }
        lore.add("");
        if (buy.isPresent()) {
            lore.add(key.isArrow() ? "<yellow>Click<gray> to buy " + ARROWS : "<yellow>Click<gray> to buy one");
            lore.add("<yellow>Shift click<gray> to buy " + (key.isArrow() ? ARROW_STACK : HANDFUL));
        }
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            lore.add("<yellow>Right click<gray> to change its prices");
        }
        bottle.editMeta(meta -> {
            List<Component> all = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
            all.addAll(lore.stream().map(PotionMenu::line).toList());
            meta.lore(all);
        });
        return bottle;
    }

    @Override
    protected void onClick(ShopService.PotionOffer offer, InventoryClickEvent event) {
        PotionKey key = offer.key();
        if (event.isRightClick() && viewer.hasPermission(PermissionNodes.ADMIN)) {
            ItemStack icon = ShopService.stackOf(key).orElseGet(() -> new ItemStack(Material.GLASS_BOTTLE));
            new PriceEditMenu(services, viewer, this, key.name(), icon, key.readable()).open();
            return;
        }
        Money price = shown.get(key);
        if (price == null) {
            return;
        }
        int count = key.isArrow() ? (event.isShiftClick() ? ARROW_STACK : ARROWS) : (event.isShiftClick() ? HANDFUL : 1);
        services.shop().buyPotion(viewer, key, count, price);
        refresh();
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Potions as the game brews them, and ominous bottles.",
                "Priced like any item: a glass bottle and a third of each ingredient,",
                "moving with supply and demand. Sell them from /sell or in hand.");
    }

    @Override
    public String describe() {
        return "potions and ominous bottles for sale";
    }
}
