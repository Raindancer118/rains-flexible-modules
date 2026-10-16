package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.service.IslandServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The team, each with their face; the owner can take somebody off the island, after being asked. */
public final class TeamMenu extends PaginatedMenu<UUID> implements IOneBlockScreen {

    private final OneBlockServices services;
    private final IslandServer islands;

    public TeamMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
    }

    @Override
    protected Component title() {
        return Component.text("Team", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Team";
    }

    private Optional<Island> island() {
        return islands.book().ofPlayer(viewer.getUniqueId());
    }

    @Override
    protected List<UUID> entries() {
        return island().map(Island::everyone).orElse(List.of());
    }

    @Override
    protected ItemStack icon(UUID member) {
        Island island = island().orElseThrow();
        String name = nameOf(member);
        boolean online = services.server().getPlayer(member) != null;
        boolean isOwner = island.owner().equals(member);
        boolean mayKick = island.owner().equals(viewer.getUniqueId()) && !isOwner;
        ItemStack head = Icons.head(member, (isOwner ? "<gold>" : "<white>") + name,
                isOwner ? "<gray>Owner" : "<gray>Team",
                online ? "<green>Online" : "<dark_gray>Offline",
                "", mayKick ? "<red>Click to take them off the island." : "");
        if (!mayKick) {
            return isOwner ? head : Icons.locked(head, "Only the owner takes somebody off the island.");
        }
        return head;
    }

    @Override
    protected void onClick(UUID member, InventoryClickEvent event) {
        Optional<Island> island = island();
        if (island.isEmpty() || !island.get().owner().equals(viewer.getUniqueId()) || island.get().owner().equals(member)) {
            return;
        }
        String name = nameOf(member);
        new ConfirmScreen(islands.brand(), viewer, this, "Take " + name + " off the island?",
                List.of("<gray>They lose access to the island and its shards.", "<gray>Invite them again to undo it."),
                () -> {
                    IOneBlockScreen.tell(services.messages(), viewer,
                            islands.islands().kick(viewer.getUniqueId(), member, name));
                    Player there = services.server().getPlayer(member);
                    if (there != null && islands.book().ofWorld(there.getWorld().getName())
                            .filter(it -> it.id().equals(island.get().id())).isPresent()) {
                        islands.travel().toWaiting(there);
                    }
                    new TeamMenu(services, islands, viewer, parent()).open();
                }).open();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No island", "<dark_gray>Start one with /island.");
    }

    private String nameOf(UUID player) {
        String name = services.server().getOfflinePlayer(player).getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    @Override
    public String describe() {
        return "an island's team";
    }
}
