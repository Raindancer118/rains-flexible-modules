package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.rules.ShopSearchRule;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code /shop search …} and the shop's search button: everything the shop has that answers to what was typed —
 * items and spawn eggs, enchanted books, potions, Rain's enchantments and packs — closest first, typos forgiven. Each
 * is drawn exactly as its own page draws it, and bought the same way.
 */
public final class ShopSearchMenu extends PaginatedMenu<ShopSearchMenu.Hit> implements IEconomyScreen {

    /** One thing found, and how close its name is. */
    sealed interface Hit permits Item, Book, Potion, Rains, Bundle {
        int score();
    }

    record Item(Material material, int score) implements Hit {
    }

    record Book(ShopService.EnchantOffer offer, int score) implements Hit {
    }

    record Potion(ShopService.PotionOffer offer, int score) implements Hit {
    }

    record Rains(ShopService.RainsOffer offer, int score) implements Hit {
    }

    record Bundle(Pack pack, int score) implements Hit {
    }

    private static final ShopSearchRule RULE = new ShopSearchRule();

    private final EconomyServices services;
    private final String typed;
    private final List<Hit> hits;
    // The other pages, only to draw a row the way they do; never opened from here.
    private final ShopItemsMenu items;
    private final EnchantMenu books;
    private final PotionMenu potions;
    private final RainsEnchantMenu rains;
    private final PacksMenu packs;

    public ShopSearchMenu(EconomyServices services, Player viewer, Menu parent, String typed) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.typed = typed == null ? "" : typed.strip();
        this.items = ShopItemsMenu.drawing(services, viewer, this);
        this.books = new EnchantMenu(services, viewer, this);
        this.potions = new PotionMenu(services, viewer, this);
        this.rains = new RainsEnchantMenu(services, viewer, this);
        this.packs = new PacksMenu(services, viewer, this);
        this.hits = find();
    }

    private List<Hit> find() {
        EconomySettings live = services.config();
        List<Hit> found = new ArrayList<>();
        for (Category category : Category.values()) {
            if (live.categoryOpen(category)) {
                services.shop().prices().tradableIn(category).forEach(name -> item(found, name));
            }
        }
        if (live.spawnEggs()) {
            services.shop().prices().tradableEggs().forEach(name -> item(found, name));
        }
        if (live.enchantBooks()) {
            for (ShopService.EnchantOffer offer : services.shop().enchantOffers()) {
                RULE.score(typed, offer.key().readable() + " enchanted book")
                        .ifPresent(score -> found.add(new Book(offer, score)));
            }
        }
        if (live.potions()) {
            for (ShopService.PotionOffer offer : services.shop().potionOffers()) {
                RULE.score(typed, offer.key().readable()).ifPresent(score -> found.add(new Potion(offer, score)));
            }
        }
        if (live.rainsEnchantments()) {
            for (ShopService.RainsOffer offer : services.shop().rainsOffers(viewer.getUniqueId())) {
                RULE.score(typed, ShopService.rainsName(offer.enchantment()) + " " + RainsEnchantMenu.roman(offer.level())
                        + " enchanted book rain's").ifPresent(score -> found.add(new Rains(offer, score)));
            }
        }
        if (services.packs().enabled()) {
            for (Pack pack : services.packs().packs()) {
                RULE.score(typed, plain(pack.title()) + " pack").ifPresent(score -> found.add(new Bundle(pack, score)));
            }
        }
        // Closest first; among equals the shop's own order stays.
        found.sort(Comparator.comparingInt(Hit::score));
        return List.copyOf(found);
    }

    private void item(List<Hit> found, String name) {
        Material material = Material.matchMaterial(name);
        if (material != null && material.isItem()) {
            RULE.score(typed, Catalogue.readable(name)).ifPresent(score -> found.add(new Item(material, score)));
        }
    }

    private static String plain(String markup) {
        return MiniMessage.miniMessage().stripTags(markup == null ? "" : markup);
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>\"" + MiniMessage.miniMessage().escapeTags(typed) + "\"");
    }

    @Override
    public String breadcrumb() {
        return "Search";
    }

    @Override
    protected List<Hit> entries() {
        return hits;
    }

    @Override
    protected ItemStack icon(Hit hit) {
        return switch (hit) {
            case Item item -> items.icon(item.material());
            case Book book -> books.icon(book.offer());
            case Potion potion -> potions.icon(potion.offer());
            case Rains offer -> rains.icon(offer.offer());
            case Bundle bundle -> packs.icon(bundle.pack());
        };
    }

    @Override
    protected void onClick(Hit hit, InventoryClickEvent event) {
        boolean admin = event.isRightClick() && viewer.hasPermission(PermissionNodes.ADMIN);
        switch (hit) {
            case Item item -> {
                if (admin) {
                    new PriceEditMenu(services, viewer, this, item.material()).open();
                } else {
                    new TradeMenu(services, viewer, this, item.material()).open();
                }
            }
            case Book book -> {
                if (admin) {
                    books.onClick(book.offer(), event);
                    return;
                }
                Optional<Money> price = services.shop().enchantBuy(viewer.getUniqueId(), book.offer().key());
                price.ifPresent(paying -> services.shop().buyEnchant(viewer, book.offer(), paying));
                refresh();
            }
            case Potion potion -> {
                if (admin) {
                    potions.onClick(potion.offer(), event);
                    return;
                }
                var key = potion.offer().key();
                var yours = services.shop().priceFor(viewer.getUniqueId(), key);
                Optional<Money> each = yours.shop().buyable() ? yours.buyFor(1) : Optional.empty();
                int count = key.isArrow() ? (event.isShiftClick() ? PotionMenu.ARROW_STACK : PotionMenu.ARROWS)
                        : (event.isShiftClick() ? PotionMenu.HANDFUL : 1);
                each.ifPresent(price -> services.shop().buyPotion(viewer, key, count, price));
                refresh();
            }
            case Rains offer -> {
                services.shop().buyRains(viewer, offer.offer());
                refresh();
            }
            case Bundle bundle -> {
                services.packs().buy(viewer, bundle.pack());
                refresh();
            }
        }
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.SPYGLASS, "<gray>Nothing called that", "<dark_gray>Try fewer or other words.");
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Everything in the shop that answers to your words:", "items, books, potions, Rain's enchantments and packs.",
                "Typos are forgiven; a number stands for a level.",
                "Staff: right click a row to change its prices.");
    }

    @Override
    public String describe() {
        return "a search across the whole shop";
    }
}
