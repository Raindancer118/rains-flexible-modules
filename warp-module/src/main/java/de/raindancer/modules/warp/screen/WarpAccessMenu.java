package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.model.WarpAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Who a warp is for: everybody, the staff, or whoever holds one particular permission.
 *
 * <h2>Why four buttons for three answers</h2>
 * Because the third answer has two useful shapes. "Its own permission" gives the warp a node named
 * after it — {@code rainswarps.warp.themine} — which is what somebody wants nine times out of ten
 * and which they should not have to remember the spelling of. The fourth button is for the tenth:
 * an existing node from the server's permissions plugin, typed in.
 *
 * <p>All four are shown to everybody who reaches this page, and the one currently in force says so
 * on itself. Greyed rather than hidden: which of them is set is exactly what somebody opened this
 * page to find out.
 */
public final class WarpAccessMenu extends Menu implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final WarpServices services;
    private final String name;

    public WarpAccessMenu(WarpServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.name = name;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Who may use it");
    }

    @Override
    public String breadcrumb() {
        return "Who may use it";
    }

    @Override
    protected void render() {
        Warp warp = services.catalogue().byName(name).orElse(null);
        if (warp == null) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This warp is gone",
                    "<gray>Somebody deleted it while this page was open."));
            return;
        }
        WarpAccess now = services.catalogue().accessOf(warp);

        option(1, Material.OAK_DOOR, "Anybody", now, WarpAccess.EVERYONE,
                "<gray>It appears on everybody's warp list.");

        option(3, Material.IRON_DOOR, "Staff only", now, WarpAccess.STAFF,
                "<gray>Needs " + WarpAccess.STAFF_PERMISSION + ".",
                "<dark_gray>Your existing staff group probably has it,",
                "<dark_gray>which is why there is one node and not one per warp.");

        WarpAccess ownNode = new WarpAccess.Needing(WarpAccess.ownPermissionFor(warp.name()));
        option(5, Material.NAME_TAG, "Its own permission", now, ownNode,
                "<gray>Needs " + ownNode.permission().orElseThrow() + ".",
                "<dark_gray>For a group that is not the staff — builders",
                "<dark_gray>who may reach the build world, say.");

        // The one that has to be typed: an arbitrary node has nothing to enumerate, which is the
        // module's only reason for ever asking in chat.
        band(MenuLayout.WHO, 7, Icons.of(Material.WRITABLE_BOOK, "<white>A permission you type",
                        "<gray>Any node your permissions plugin already knows.",
                        "",
                        "<gray>Click to type it."),
                click -> askForAPermission());
    }

    /**
     * One of the three ready-made answers.
     *
     * <p>The one in force is shown greyed with the reason, rather than left off — which of them is
     * set is what somebody opened this page to see.
     */
    private void option(int column, Material icon, String label, WarpAccess now, WarpAccess wanted,
                        String... why) {
        boolean already = now.equals(wanted);
        java.util.List<String> lore = new java.util.ArrayList<>(java.util.List.of(why));
        lore.add("");
        lore.add(already ? "<green>This is how it is set." : "<gray>Click to set it.");

        band(MenuLayout.WHO, column, !already,
                Icons.of(icon, "<white>" + label, lore),
                "That is already how it is set",
                click -> {
                    services.admin().setAccess(viewer, name, wanted);
                    leave();
                });
    }

    private void askForAPermission() {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "warps", TO_ANSWER,
                answer -> {
                    if (answer == null || answer.isBlank()) {
                        services.messages().send(viewer, "warps.access-empty");
                    } else {
                        services.admin().setAccess(viewer, name, new WarpAccess.Needing(answer));
                    }
                    open();
                },
                this::open);
        if (!asking) {
            services.messages().send(viewer, "warps.busy");
            open();
            return;
        }
        services.messages().send(viewer, "warps.ask-permission");
    }

    @Override
    public String describe() {
        return "who may use one warp";
    }
}
