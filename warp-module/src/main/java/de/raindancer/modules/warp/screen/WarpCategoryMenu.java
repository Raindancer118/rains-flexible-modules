package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.warp.WarpServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The categories, for a server with more warps than fit on a list.
 *
 * <h2>Why the categories are only ever the ones with something in them</h2>
 * Because a page listing "Staff" with nothing behind it tells an ordinary player precisely what the
 * hiding was for. The counting is done over the warps this viewer can see, so a category whose only
 * warps are staff warps does not exist as far as everybody else is concerned.
 *
 * <h2>Why this is one level and not two</h2>
 * A category holds warps; a warp is not another category. Three levels means nobody can say where
 * anything lives — so this page opens a filtered {@code WarpListMenu} and that is the end of it.
 */
public final class WarpCategoryMenu extends PaginatedMenu<String> implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What the warps filed under nothing are called on this page. */
    private static final String EVERYTHING_ELSE = "Everything else";

    private final WarpServices services;

    public WarpCategoryMenu(WarpServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Categories");
    }

    @Override
    public String breadcrumb() {
        return "Categories";
    }

    @Override
    protected List<String> entries() {
        List<String> found = new ArrayList<>(
                services.catalogue().categoriesVisibleTo(viewer::hasPermission, services.access()));
        found.sort(String.CASE_INSENSITIVE_ORDER);
        // Last, always. The pile of things nobody has filed belongs after the things somebody has.
        if (services.catalogue().hasUncategorised(viewer::hasPermission, services.access())) {
            found.add(EVERYTHING_ELSE);
        }
        return found;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing is filed under anything",
                "<gray>Every warp you can see is on the one list.",
                "<dark_gray>An admin files a warp with /warp category.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        // The way out of this page is the page behind it, which is the list itself.
        leave();
    }

    @Override
    protected ItemStack icon(String category) {
        int howMany = countIn(category);
        return Icons.of(Material.BOOKSHELF, "<white>" + category,
                "<gray>" + howMany + (howMany == 1 ? " warp" : " warps"),
                "",
                "<gray>Click to see them.");
    }

    /**
     * Opened with {@code this} as the parent, rather than through the opener.
     *
     * <p>The opener passes no parent, and a parentless menu draws no Back button at all — which
     * here would be a category page you can only leave by closing the window. The opener is for
     * entry points from a command, where there really is nothing behind the page.
     */
    @Override
    protected void onClick(String category, InventoryClickEvent event) {
        WarpListMenu.inCategory(services, viewer, this,
                category.equals(EVERYTHING_ELSE) ? null : category).open();
    }

    private int countIn(String category) {
        return services.catalogue().inCategory(
                        category.equals(EVERYTHING_ELSE) ? null : category,
                        viewer::hasPermission, services.access())
                .size();
    }

    @Override
    public String describe() {
        return "the categories the warps are grouped into";
    }
}
