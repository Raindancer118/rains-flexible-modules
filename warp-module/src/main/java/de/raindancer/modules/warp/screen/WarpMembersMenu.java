package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.model.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The people its owner let into a private warp: one head each, and a way to add somebody. */
public final class WarpMembersMenu extends PaginatedMenu<UUID> implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WarpServices services;
    private final String name;

    public WarpMembersMenu(WarpServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
    }

    private Warp warp() {
        return services.catalogue().byName(name).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        return "Who may use it";
    }

    @Override
    protected List<UUID> entries() {
        Warp warp = warp();
        return warp == null ? List.of() : new ArrayList<>(warp.members());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Only you so far",
                "<gray>Click here, or on the button below,",
                "<gray>to let somebody in.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        pickSomebody();
    }

    @Override
    protected ItemStack icon(UUID member) {
        OfflinePlayer player = services.server().getOfflinePlayer(member);
        String shown = player.getName() == null ? member.toString().substring(0, 8) : player.getName();
        return Icons.of(Material.PLAYER_HEAD, "<white>" + shown,
                player.isOnline() ? "<green>Online" : "<dark_gray>Offline",
                "",
                "<gray>Click to take them off the list.");
    }

    @Override
    protected void onClick(UUID member, InventoryClickEvent event) {
        OfflinePlayer player = services.server().getOfflinePlayer(member);
        services.admin().removeMember(viewer, name, member,
                player.getName() == null ? member.toString() : player.getName());
        new WarpMembersMenu(services, viewer, parent(), name).open();
    }

    @Override
    protected void decorate() {
        super.decorate();
        toolbar(4, Icons.of(Material.PLAYER_HEAD, "<white>Let somebody in",
                        "<gray>They can then use this warp,",
                        "<gray>and see it on their list."),
                click -> pickSomebody());
    }

    private void pickSomebody() {
        Warp warp = warp();
        if (warp == null) {
            services.messages().send(viewer, "warps.unknown", "name", name);
            return;
        }
        List<UUID> already = new ArrayList<>(warp.members());
        warp.owner().ifPresent(already::add);
        new PlayerChooser(viewer, services.brand(), this, "Let somebody into " + warp.label(), already,
                chosen -> {
                    services.admin().addMember(viewer, name, chosen.id(), chosen.name());
                    new WarpMembersMenu(services, viewer, parent(), name).open();
                }).open();
    }

    @Override
    public String describe() {
        return "the people let into one private warp";
    }
}
