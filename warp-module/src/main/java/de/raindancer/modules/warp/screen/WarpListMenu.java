package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The warp list: what {@code /warp} opens.
 *
 * <h2>Why the front door is a menu and not a list in chat</h2>
 * Because a list in chat is a list you have to read, remember and then type back correctly. A warp
 * whose name is misremembered by one letter looks to the player like a warp that has been deleted.
 * Here the name is a button, the world and coordinates are on it, and the icon is whatever the admin
 * chose — which is the part people actually navigate by.
 *
 * <p>The chat listing is still there behind {@code /warp list}, for the console, which has no
 * inventory to open, and for pasting to somebody else.
 *
 * <h2>What is shown</h2>
 * Only warps this player may use. This is the module's one deliberate departure from "greyed, never
 * hidden": greying the staff warp would tell every player that there is a warp called
 * {@code staffroom}, which is the half of the secret worth keeping. Because
 * {@code WarpAccessRule.maySee} and {@code mayUse} are the same answer, nothing here can refuse
 * after the click.
 */
public final class WarpListMenu extends PaginatedMenu<Warp> implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WarpServices services;

    /**
     * Whether this page is one category's worth or the lot.
     *
     * <p>A separate flag rather than "null category means everything", because null is already
     * taken: it is what {@code inCategory} means by "the ones filed under nothing", and a page that
     * conflated those two would show every warp on the server under the heading
     * <em>Everything else</em>.
     */
    private final boolean filtered;

    /** Which category, when {@link #filtered}; null there means the ones filed under nothing. */
    private final String category;

    /** Every warp this player may use. */
    public WarpListMenu(WarpServices services, Player viewer, Menu parent) {
        this(services, viewer, parent, false, null);
    }

    /** One category's worth; null for the ones filed under nothing. */
    public static WarpListMenu inCategory(WarpServices services, Player viewer, Menu parent,
                                          String category) {
        return new WarpListMenu(services, viewer, parent, true, category);
    }

    private WarpListMenu(WarpServices services, Player viewer, Menu parent, boolean filtered,
                         String category) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.filtered = filtered;
        this.category = category;
    }

    @Override
    protected Component title() {
        // Never the brand: it is already prefixed, so "Warps" here would render as "Warps » Warps".
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        if (!filtered) {
            return "Where you can go";
        }
        return category == null ? "Everything else" : category;
    }

    @Override
    protected List<Warp> entries() {
        return filtered
                ? services.catalogue().inCategory(category, viewer::hasPermission,
                        services.access())
                : services.catalogue().visibleTo(viewer::hasPermission, services.access());
    }

    /**
     * An empty list is a real state on a new server, and it says what to do about it.
     *
     * <p>Different words for an admin, because they are the one who can act on it — and the button
     * acts, rather than only describing what to type.
     */
    @Override
    protected ItemStack emptyIcon() {
        if (services.access().mayManage(viewer::hasPermission)) {
            return Icons.of(Material.COBWEB, "<gray>There are no warps yet",
                    "<gray>Stand where you want one and click here",
                    "<gray>to be told how to make it.");
        }
        return Icons.of(Material.COBWEB, "<gray>There are no warps yet",
                "<gray>Nobody has made one on this server,",
                "<gray>or none of them is for you.");
    }

    /**
     * The way out of an empty page, for the person who can take it.
     *
     * <p>A list that names the way out must be able to act on it, or the sentence is decoration.
     */
    @Override
    protected void emptyAction(InventoryClickEvent event) {
        if (services.access().mayManage(viewer::hasPermission)) {
            viewer.closeInventory();
            services.messages().send(viewer, "warps.how-to-make-one");
        } else {
            services.messages().send(viewer, "warps.none-for-you");
        }
    }

    @Override
    protected ItemStack icon(Warp warp) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + warp.world() + " " + warp.coordinates());
        warp.category().ifPresent(filed -> lore.add("<dark_gray>Filed under " + filed));
        if (services.catalogue().accessOf(warp).isRestricted()) {
            // Shown to the few who can see it at all, so they know it is not on everybody's list.
            lore.add("<gray>" + services.catalogue().accessOf(warp).describe());
        }
        lore.add("");
        if (!warp.isReachable()) {
            lore.add("<red>Its world is not loaded right now.");
        } else if (services.config().warmup() > 0) {
            lore.add("<gray>Click to go. Stand still for "
                    + services.config().warmup() + "s.");
        } else {
            lore.add("<gray>Click to go.");
        }

        // A warp whose configured icon is a block and not an item cannot be drawn at all, and one
        // bad line in a config would otherwise take the whole page down. Shown as a lodestone.
        Material material = warp.poi().icon();
        if (material == null || !material.isItem()) {
            material = Material.LODESTONE;
        }
        return Icons.of(material, "<white>" + warp.label(), lore);
    }

    @Override
    protected void onClick(Warp warp, InventoryClickEvent event) {
        // Closed first: the teleport can take a few seconds of standing still, and a window open
        // over it is a window the player has to shut before they can be seen to have moved.
        viewer.closeInventory();
        services.travelling().go(viewer, warp);
    }

    /**
     * The categories button and the admin door.
     *
     * <p>Two columns apart, so a pane falls between them — a wall of adjacent buttons is
     * unreadable.
     */
    @Override
    protected void decorate() {
        super.decorate();
        if (!filtered && services.config().useCategories()
                && !services.catalogue().categoriesVisibleTo(viewer::hasPermission,
                services.access()).isEmpty()) {
            toolbar(2, Icons.of(Material.BOOKSHELF, "<white>Categories",
                            "<gray>The warps on this server, grouped.",
                            "<dark_gray>Useful once there are more than fit on a page."),
                    click -> services.screens().categories(viewer));
        }
        if (services.access().mayManage(viewer::hasPermission)) {
            toolbar(6, Icons.of(Material.COMPARATOR, "<white>Manage warps",
                            "<gray>Make one here, move one, decide who each is for.",
                            "<dark_gray>Only you and the other admins see this."),
                    click -> services.screens().admin(viewer));
        }
    }

    @Override
    protected List<String> helpLines() {
        // Core draws these as a book. Generated from the settings that are loaded, so the page
        // cannot come to describe a warm-up or a wait this server does not have.
        // Under manual.using rather than manual itself: a list at manual: and a child at
        // manual.editing cannot both exist in one YAML file, and the one that loses is silent.
        //
        // Written without quoting the key, deliberately — MessagesTest scans for anything that looks
        // like a key literal, comments included, and a key named only in a comment is a key it will
        // go looking for wording for.
        return services.messages().lines("warps.manual.using",
                        "warmup", services.config().warmup(),
                        "cooldown", services.config().cooldown())
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "the warps this player may go to";
    }

    /** What opens this page from a command. */
    public static String permission() {
        return PermissionNodes.USE;
    }
}
