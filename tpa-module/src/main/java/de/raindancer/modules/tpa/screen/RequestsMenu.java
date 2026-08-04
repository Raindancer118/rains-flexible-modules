package de.raindancer.modules.tpa.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What has been asked of this player, and what they have asked.
 *
 * <h2>Why the chat lines still exist</h2>
 * This page is for somebody who has come back to the keyboard and wants to see everything at once. The
 * clickable chat line is for somebody who is looking at the screen when the request arrives — and it
 * has to be chat rather than the action bar, because an action bar cannot be clicked and is gone in
 * three seconds. Both, deliberately.
 */
public final class RequestsMenu extends PaginatedMenu<TpaRequest> implements ITpaScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TpaServices services;

    public RequestsMenu(TpaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Requests");
    }

    @Override
    public String breadcrumb() {
        return "Requests";
    }

    /** What people have asked of them, newest first. */
    @Override
    protected List<TpaRequest> entries() {
        return services.requests().to(viewer.getUniqueId());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody is waiting on you",
                "<gray>When somebody asks, it appears here",
                "<gray>and in chat, where you can click it.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        leave();
    }

    @Override
    protected ItemStack icon(TpaRequest request) {
        String asker = services.prefs().nameOf(request.from());
        long left = request.secondsLeft(System.currentTimeMillis());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + (request.kind() == TpaKind.TO
                ? "They would come to you."
                : "They would like you to go to them."));
        lore.add("<dark_gray>" + left + "s left to answer.");
        lore.add("");
        lore.add("<gray>Click to accept.");
        lore.add("<gray>Right click to refuse.");
        lore.add("<gray>Shift click to block them.");

        return Icons.head(request.from(), "<white>" + asker, lore);
    }

    @Override
    protected void onClick(TpaRequest request, InventoryClickEvent event) {
        if (event.isShiftClick()) {
            // By uuid, never by name: getOfflinePlayer(String) blocks on a lookup against Mojang,
            // from what on Folia may be a region thread.
            services.prefs().block(viewer, services.server().getOfflinePlayer(request.from()));
            refresh();
            return;
        }
        viewer.closeInventory();
        if (event.isRightClick()) {
            services.asking().deny(viewer, request.from());
            return;
        }
        services.asking().accept(viewer, request.from());
    }

    /** Their own outstanding request, which is the other half of what this page is for. */
    @Override
    protected void decorate() {
        super.decorate();
        services.prefs().outgoingOf(viewer.getUniqueId()).ifPresent(mine -> {
            String asked = services.prefs().nameOf(mine.to());
            toolbar(4, Icons.of(Material.PAPER, "<white>You asked " + asked,
                            "<gray>" + (mine.kind() == TpaKind.TO
                                    ? "To go to them."
                                    : "For them to come to you."),
                            "<dark_gray>" + mine.secondsLeft(System.currentTimeMillis())
                                    + "s left for them to answer.",
                            "",
                            "<gray>Click to take it back."),
                    click -> {
                        services.asking().cancel(viewer);
                        refresh();
                    });
        });
    }

    @Override
    public String describe() {
        return "what has been asked of this player, and what they have asked";
    }
}
