package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.store.SponsorShopStore.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every offer in the sponsor shop, editable by an admin: on or off, one click; changed or removed with a
 * click more.
 *
 * <h2>Why this reads and writes typed {@link ShopItem}s, not lines of text</h2>
 * The source engine's editor manipulated {@code sponsors.shop.items} as raw strings — the same free-text
 * line {@link ShopEntryMenu} builds from a chooser here, so nothing on this page ever hand-writes the
 * {@code id|reward|cost|name} syntax. {@link SponsorShopStore} parses and serialises it once, and this
 * screen only ever calls {@link SponsorShopStore#load} and {@link SponsorShopStore#save}.
 */
public final class ShopEditorMenu extends PaginatedMenu<ShopItem> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SponsorShopStore shopStore;
    private final CustomItems customItems;
    private final ChatPrompts prompts;

    public ShopEditorMenu(Player viewer, Brand brand, Menu parent, SponsorShopStore shopStore,
                          CustomItems customItems, ChatPrompts prompts) {
        super(viewer, brand, parent);
        this.shopStore = shopStore;
        this.customItems = customItems;
        this.prompts = prompts;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Sponsor shop — editing");
    }

    @Override
    public String breadcrumb() {
        return "Shop editor";
    }

    private Set<String> knownCustomItemIds() {
        return customItems.all().stream().map(CustomItem::id)
                .map(id -> id.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    @Override
    protected List<ShopItem> entries() {
        return shopStore.load(knownCustomItemIds());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No offers yet", "<gray>Use one of the buttons below.");
    }

    @Override
    protected ItemStack icon(ShopItem item) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>id: " + item.id());
        lore.add("<gray>" + ShopMenu.describe(item.reward()));
        if (!item.enchantments().isEmpty()) {
            lore.add("<light_purple>Enchanted: " + String.join(", ", item.enchantments()));
        }
        lore.add("<gold>Cost: " + item.cost() + " token(s)");
        lore.add(item.enabled() ? "<green>On sale" : "<dark_gray>Switched off");
        lore.add("");
        lore.add("<aqua>Left-click: on/off");
        lore.add("<aqua>Right-click: edit");
        lore.add("<aqua>Shift-left-click: remove");

        return Icons.of(ShopMenu.iconMaterial(item.reward()),
                (item.enabled() ? "<yellow>" : "<dark_gray>") + item.displayName(), lore);
    }

    @Override
    protected void onClick(ShopItem item, InventoryClickEvent event) {
        List<ShopItem> current = entries();
        int index = current.indexOf(item);
        if (index < 0) {
            return;
        }
        if (event.isShiftClick() && event.isLeftClick()) {
            new ConfirmScreen(viewer, brand(), this, "<yellow>Remove \"" + item.displayName() + "\"?",
                    List.of("<gray>The offer is gone for good."),
                    () -> {
                        List<ShopItem> updated = new ArrayList<>(current);
                        updated.remove(index);
                        shopStore.save(updated);
                        open();
                    }).open();
        } else if (event.isRightClick()) {
            new ShopEntryMenu(viewer, brand(), this, shopStore, customItems, prompts, index).open();
        } else {
            List<ShopItem> updated = new ArrayList<>(current);
            updated.set(index, new ShopItem(item.id(), item.reward(), item.cost(), item.displayName(),
                    !item.enabled(), item.enchantments()));
            shopStore.save(updated);
            refresh();
        }
    }

    @Override
    protected void render() {
        super.render();
        toolbar(1, Icons.of(Material.NETHER_STAR, "<light_purple>+ Custom item",
                        "<gray>Reward one of this server's custom items."),
                click -> new ShopEntryMenu(viewer, brand(), this, shopStore, customItems, prompts,
                        ShopEntryMenu.Kind.CUSTOM).open());
        toolbar(3, Icons.of(Material.CHEST, "<green>+ Material",
                        "<gray>Reward any vanilla item."),
                click -> new ShopEntryMenu(viewer, brand(), this, shopStore, customItems, prompts,
                        ShopEntryMenu.Kind.MATERIAL).open());
        toolbar(5, Icons.of(Material.POTION, "<light_purple>+ Potion",
                        "<gray>Reward a drinkable, splash or lingering potion."),
                click -> new ShopEntryMenu(viewer, brand(), this, shopStore, customItems, prompts,
                        ShopEntryMenu.Kind.POTION).open());
        toolbar(7, Icons.of(Material.EXPERIENCE_BOTTLE, "<yellow>+ Effect",
                        "<gray>Reward a potion effect straight onto the tribute."),
                click -> new ShopEntryMenu(viewer, brand(), this, shopStore, customItems, prompts,
                        ShopEntryMenu.Kind.EFFECT).open());
    }

    @Override
    public String describe() {
        return "the sponsor shop's offers, editable one at a time";
    }
}
