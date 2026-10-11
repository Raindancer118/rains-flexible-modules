package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * An owner setting one item's prices. Everything here writes the same lists in the settings file an owner
 * can edit by hand ({@code shop.values}, {@code shop.buy-prices}, {@code shop.sell-prices}, …) — one
 * source of truth. Left click sets, right click clears back to automatic.
 */
public final class PriceEditMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    /** The item's material name, or an enchanted book's key — the name its price lines use. */
    private final String name;
    private final ItemStack icon;
    private final String label;

    public PriceEditMenu(EconomyServices services, Player viewer, Menu parent, Material material) {
        this(services, viewer, parent, material.name(), new ItemStack(material), Catalogue.readable(material.name()));
    }

    public PriceEditMenu(EconomyServices services, Player viewer, Menu parent, String name, ItemStack icon, String label) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
        this.icon = icon;
        this.label = label;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Prices · "
                + MiniMessage.miniMessage().escapeTags(label));
    }

    @Override
    public String breadcrumb() {
        return "Prices";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        PriceTag tag = services.shop().tag(name);
        ItemStack subject = icon.clone();
        subject.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize("<white>" + MiniMessage.miniMessage().escapeTags(label))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.stream.Stream.of(
                "<gray>Value: " + Mini.of(currency.render(tag.value())) + " <dark_gray>(" + tag.source().name().toLowerCase() + ")",
                "<gray>Buys for: " + Mini.of(currency.render(tag.buy())),
                "<gray>Sells for: " + Mini.of(currency.render(tag.sell())),
                    tag.buyable() || tag.sellable() ? "" : "<yellow>Not in the shop yet: give it a value or a buy price.")
                    .map(line -> MiniMessage.miniMessage().deserialize(line)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        });
        set(MenuLayout.HEADER_SUBJECT, subject);

        priceButton(1, Material.GOLD_INGOT, "Value", "shop.values", live.customValues(),
                "What it is worth before markups; crafted items follow it.");
        priceButton(4, Material.EMERALD, "Buy price", "shop.buy-prices", live.buyPrices(),
                "Exactly what one costs, ignoring supply and demand.");
        priceButton(7, Material.CHEST, "Sell price", "shop.sell-prices", live.sellPrices(),
                "Exactly what the shop pays for one.");

        boolean notSold = listed(live.notSold());
        boolean notBought = listed(live.notBought());
        band(MenuLayout.LAND, 3, Icons.of(notSold ? Material.RED_DYE : Material.LIME_DYE,
                notSold ? "<red>Not sold to players" : "<green>Sold to players", "<gray>Click to switch."), click -> {
            services.shop().setListed("shop.not-sold", name, !notSold);
            refresh();
        });
        band(MenuLayout.LAND, 5, Icons.of(notBought ? Material.RED_DYE : Material.LIME_DYE,
                notBought ? "<red>Not bought from players" : "<green>Bought from players", "<gray>Click to switch."), click -> {
            services.shop().setListed("shop.not-bought", name, !notBought);
            refresh();
        });
    }

    private void priceButton(int column, Material icon, String what, String key, List<String> lines, String meaning) {
        Currency currency = services.currency();
        Optional<Money> custom = PricedNames.parse(lines, currency).of(name);
        band(MenuLayout.RULES, column, Icons.of(icon, "<white>" + what,
                custom.map(price -> "<yellow>Custom: " + Mini.of(currency.render(price))).orElse("<gray>Automatic"),
                "<dark_gray>" + meaning, "", "<yellow>Click<gray> to set it",
                "<yellow>Right click<gray> to make it automatic again"), click -> {
            if (click.isRightClick()) {
                services.shop().setCustom(key, name, null);
                refresh();
                return;
            }
            MoneyPrompt.ask(viewer, what + " of " + label, currency, price -> {
                services.shop().setCustom(key, name, price);
                open();
            }, this::open);
        });
    }

    private boolean listed(List<String> names) {
        return names.stream().anyMatch(each -> each.strip().equalsIgnoreCase(name));
    }

    @Override
    public String describe() {
        return "an owner setting one item's prices";
    }
}
