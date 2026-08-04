package de.raindancer.modules.homes.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.homes.HomeServices;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.HomeIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What one home shows as in the list.
 *
 * <h2>Why a curated set and not every block on the server</h2>
 * Because a page of every material is a page nobody finds anything on. These are the ones that mean
 * something about a place — a bed, a door, a furnace, a beacon — and they are in the order the old
 * plugin had them, so somebody who knew where the bed was still does.
 *
 * <p>Core's {@code ItemChooser} would offer everything, which is right for a warp icon an admin sets
 * once and wrong for the thing a player picks to tell their three homes apart at a glance.
 */
public final class HomeIconMenu extends PaginatedMenu<Material> implements IHomeScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final HomeServices services;
    private final String name;

    public HomeIconMenu(HomeServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
    }

    private Home home() {
        return services.homes().find(viewer.getUniqueId(), name).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>A block for " + name);
    }

    @Override
    public String breadcrumb() {
        return "A block for " + name;
    }

    @Override
    protected List<Material> entries() {
        // Only what can actually be drawn. A material that is a block and not an item throws when it
        // becomes an ItemStack, and one bad entry would take the whole page down.
        return HomeIcons.CHOICES.stream().filter(Material::isItem).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        // Only reachable on a server whose item registry has nothing this module knows about, which
        // would be a very strange server — but a blank page with no explanation is worse.
        return Icons.of(Material.COBWEB, "<gray>Nothing to choose from",
                "<gray>This server has none of the blocks",
                "<gray>this module knows how to offer.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        leave();
    }

    @Override
    protected ItemStack icon(Material material) {
        Home home = home();
        boolean chosen = home != null && home.icon()
                .map(icon -> icon.equalsIgnoreCase(material.name()))
                .orElse(false);
        List<String> lore = new ArrayList<>();
        if (chosen) {
            lore.add("<green>This is the one.");
        } else {
            lore.add("<gray>Click to use this.");
        }
        return Icons.of(material, (chosen ? "<green>" : "<white>") + HomeIcons.readable(material),
                lore);
    }

    @Override
    protected void onClick(Material material, InventoryClickEvent event) {
        services.keeping().setIcon(viewer, name, material.name());
        // Said out loud as well as shown. Going straight back with no word is a click that looks like
        // nothing happened — the page behind does show the new block, but only to somebody who was
        // already looking at that button.
        services.messages().send(viewer, "homes.icon-set",
                "name", name, "icon", HomeIcons.readable(material));
        // Back to the home's own page, which is where somebody came from and where they can see the
        // choice they just made on the button.
        leave();
    }

    /** The way back to no chosen block at all. */
    @Override
    protected void decorate() {
        super.decorate();
        Home home = home();
        boolean hasOne = home != null && home.hasIcon();
        toolbar(4, hasOne
                        ? Icons.of(Material.STRUCTURE_VOID, "<white>Whatever the world is",
                                "<gray>Grass, netherrack or end stone, chosen",
                                "<gray>by the world the home is in.",
                                "",
                                "<gray>Click to go back to that.")
                        : Icons.locked(Icons.of(Material.STRUCTURE_VOID,
                                        "<white>Whatever the world is",
                                        "<gray>Grass, netherrack or end stone, chosen",
                                        "<gray>by the world the home is in."),
                                "That is already how it is set"),
                click -> {
                    if (!hasOne) {
                        return;
                    }
                    services.keeping().setIcon(viewer, name, null);
                    leave();
                });
    }

    @Override
    public String describe() {
        return "what one home shows as in the list";
    }
}
