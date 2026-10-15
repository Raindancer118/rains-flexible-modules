package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.service.ClientShop;
import de.raindancer.modules.economy.service.ShopDesk;
import de.raindancer.modules.economy.service.ShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One item of the bank's shop: buy one to a stack, sell one to everything you carry — each button priced by the bank
 * when the page opened. A purchase pays no more than the button said; a price that rose since is refused and the page
 * asks the bank again.
 */
public final class ClientTradeMenu extends Menu implements IEconomyScreen {

    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};

    private final ClientShop shop;
    private final Brand brand;
    private final Material material;
    private final ShopDesk.Quote quote;

    private ClientTradeMenu(ClientShop shop, Brand brand, Player viewer, Menu parent, Material material,
                            ShopDesk.Quote quote) {
        super(viewer, brand, parent);
        this.shop = shop;
        this.brand = brand;
        this.material = material;
        this.quote = quote;
    }

    /** Asks the bank for this player's prices, then opens. */
    public static void open(ClientShop shop, Brand brand, Player viewer, Menu parent, Material material) {
        int stack = Math.max(1, material.getMaxStackSize());
        List<Integer> buy = new ArrayList<>();
        for (int amount : AMOUNTS) {
            if (amount == 1 || amount <= stack) {
                buy.add(amount);
            }
        }
        List<Integer> sell = new ArrayList<>(buy);
        int carrying = ShopService.carryingIn(viewer, material);
        if (carrying > 0 && !sell.contains(carrying)) {
            sell.add(carrying);
        }
        shop.quote(viewer, material, buy, sell, quote ->
                new ClientTradeMenu(shop, brand, viewer, parent, material, quote).open());
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>" + Catalogue.readable(material.name()));
    }

    @Override
    public String breadcrumb() {
        return Catalogue.readable(material.name());
    }

    @Override
    protected void render() {
        int carrying = ShopService.carryingIn(viewer, material);
        List<String> header = new ArrayList<>();
        price(quote.buys(), 1).ifPresentOrElse(one -> header.add("<gray>Buy one: " + one.shown()),
                () -> header.add("<dark_gray>Not sold"));
        price(quote.sells(), 1).ifPresentOrElse(one -> header.add("<gray>Sell one: " + one.shown()),
                () -> header.add("<dark_gray>Not bought"));
        header.addAll(quote.notes());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(material, "<white>" + Catalogue.readable(material.name()), header));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CHEST, "<white>You carry " + carrying));
        int column = 1;
        for (ShopDesk.Price buying : quote.buys()) {
            if (buying.count() == carrying && !isStep(buying.count())) {
                continue;
            }
            band(MenuLayout.WHO, column, quote.buyable(), Icons.of(Material.LIME_STAINED_GLASS_PANE,
                            "<green>Buy " + buying.count(), "<gray>For " + buying.shown()), "The shop does not sell this.",
                    click -> shop.buy(viewer, material, buying.count(), buying.minor())
                            .thenAccept(done -> again()));
            Optional<ShopDesk.Price> selling = price(quote.sells(), buying.count());
            band(MenuLayout.LAND, column, quote.sellable() && selling.isPresent() && carrying >= buying.count(),
                    Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Sell " + buying.count(),
                            "<gray>For " + selling.map(ShopDesk.Price::shown).orElse("—")),
                    quote.sellable() ? "You do not carry that many." : "The shop does not buy this.",
                    click -> shop.sell(viewer, material, buying.count()).thenAccept(done -> again()));
            column++;
        }
        Optional<ShopDesk.Price> all = price(quote.sells(), carrying);
        band(MenuLayout.LAND, 7, quote.sellable() && carrying > 0 && all.isPresent(),
                Icons.of(Material.HOPPER, "<red>Sell all " + carrying, "<gray>For " + all.map(ShopDesk.Price::shown)
                        .orElse("—")), quote.sellable() ? "You carry none." : "The shop does not buy this.",
                click -> shop.sell(viewer, material, carrying).thenAccept(done -> again()));
    }

    private static boolean isStep(int count) {
        for (int amount : AMOUNTS) {
            if (amount == count) {
                return true;
            }
        }
        return false;
    }

    /** After a trade: the bank's prices again, since this one moved them. */
    private void again() {
        if (viewer.isOnline()) {
            open(shop, brand, viewer, parent(), material);
        }
    }

    private static Optional<ShopDesk.Price> price(List<ShopDesk.Price> prices, int count) {
        return prices.stream().filter(price -> price.count() == count).findFirst();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Green buys, red sells.", "Prices are the bank's, asked when the page opened.");
    }

    @Override
    public String describe() {
        return "buying and selling one item of the bank's shop";
    }
}
