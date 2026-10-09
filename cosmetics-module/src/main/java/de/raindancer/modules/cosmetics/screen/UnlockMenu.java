package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.Unlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** What this server sells, with the price in the lore. Click one you do not have to buy it, after asking. */
public final class UnlockMenu extends PaginatedMenu<String> implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public UnlockMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Unlocks");
    }

    @Override
    public String breadcrumb() {
        return "Unlocks";
    }

    @Override
    protected List<String> entries() {
        return services.unlocks().offered();
    }

    @Override
    protected void render() {
        super.render();
        if (services.mayOpenSettings(viewer)) {
            toolbar(6, Icons.of(Material.COMPARATOR, "<white>Prices and the sales switch",
                            "<gray>Opens the Prices settings.", "",
                            "<gray>Only people with the settings permission see this."),
                    click -> new SettingsMenu(viewer, services.brand(), services.core().chatFor(services.brand()),
                            services.core().settingsNavigation(), "cosmetics/prices", this).open());
        }
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing is for sale on this server",
                "<gray>An owner sets prices under Prices in /settings,",
                "<gray>or price: on a preset in the cosmetics config.yml.");
    }

    @Override
    protected ItemStack icon(String key) {
        boolean owned = services.unlocks().owns(viewer.getUniqueId(), key);
        if (!services.unlocks().priced(key)) {
            return Icons.locked(Icons.of(materialOf(key), "<white>" + services.unlocks().title(key),
                    "<dark_gray>Decided by permission, not bought."), services.unlocks().selling()
                    ? "No price set for this." : "Cosmetics are not for sale on this server.");
        }
        ItemStack icon = Icons.of(materialOf(key), "<white>" + services.unlocks().title(key),
                "<gold>Price: <white>" + services.unlocks().priceText(key),
                "<dark_gray>Bought once, yours for good.", "",
                owned ? "<green>You have this." : "<yellow>Click<gray> to buy it, you are asked first.");
        if (owned) {
            icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return icon;
    }

    @Override
    protected void onClick(String key, InventoryClickEvent event) {
        if (!services.unlocks().priced(key)) {
            services.messages().send(viewer, services.unlocks().selling()
                    ? "cosmetics.unlock.no-price" : "cosmetics.unlock.switched-off", "what", services.unlocks().title(key));
            return;
        }
        if (services.unlocks().owns(viewer.getUniqueId(), key)) {
            services.unlocks().purchase(viewer, key);
            return;
        }
        new ConfirmScreen(services, viewer, this, "Buy " + services.unlocks().title(key) + "?",
                List.of("<gray>It costs <white>" + services.unlocks().priceText(key) + "<gray>.",
                        "<gray>It is yours for good once bought."),
                () -> {
                    services.unlocks().purchase(viewer, key);
                    open();
                }).open();
    }

    private static Material materialOf(String key) {
        if (key.equals(Unlock.PARTICLES)) {
            return Material.BLAZE_POWDER;
        }
        if (key.equals(Unlock.TELEPORT)) {
            return Material.ENDER_PEARL;
        }
        return key.startsWith("decoration.") ? Material.BRUSH : Material.NAME_TAG;
    }

    @Override
    public String describe() {
        return "what this server sells in cosmetics, and what you have bought";
    }
}
