package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is here and what they are: racing, not racing ({@code /speedrunspectate}), released from the
 * freeze ({@code /lemmemove}), or offline. Click to switch somebody between racing and not; staff
 * shift-click to release or freeze them.
 */
public final class SpeedrunRosterMenu extends PaginatedMenu<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;

    public SpeedrunRosterMenu(SpeedrunLobby lobby, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Who is here");
    }

    @Override
    public String breadcrumb() {
        return "Roster";
    }

    /** The racers of the run under way, then everybody in the run's worlds. */
    @Override
    protected List<UUID> entries() {
        Set<UUID> everybody = new LinkedHashSet<>();
        lobby.session().ifPresent(session -> everybody.addAll(session.participants()));
        SpeedrunWorlds worlds = SpeedrunWorlds.around(lobby.config().worldName());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (worlds.contains(player.getWorld().getName())) {
                everybody.add(player.getUniqueId());
            }
        }
        return new ArrayList<>(everybody);
    }

    @Override
    protected void decorate() {
        List<UUID> all = entries();
        long racing = all.stream().filter(id -> !lobby.isSpectator(id)).count();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.PLAYER_HEAD, "<white>" + racing + " racing",
                "<gray>" + (all.size() - racing) + " not racing",
                "<gray>Pressing start sweeps up everybody racing", "<gray>who stands in the lobby world."));
        super.decorate();
    }

    @Override
    protected ItemStack icon(UUID id) {
        OfflinePlayer who = Bukkit.getOfflinePlayer(id);
        String name = who.getName() == null ? id.toString().substring(0, 8) : who.getName();
        boolean inRun = lobby.session().map(session -> session.participants().contains(id)).orElse(false);
        List<String> lore = new ArrayList<>();
        lore.add(inRun ? "<green>In the run under way" : lobby.isSpectator(id) ? "<gray>Not racing" : "<green>Racing next");
        if (Bukkit.getPlayer(id) == null) {
            lore.add("<dark_gray>Offline");
        }
        if (lobby.isReleased(id)) {
            lore.add("<yellow>Released from the freeze");
        }
        lore.add("");
        if (id.equals(viewer.getUniqueId()) || viewer.hasPermission(PermissionNodes.ADMIN)) {
            lore.add("<dark_gray>Click: " + (lobby.isSpectator(id) ? "race again" : "not racing"));
        }
        if (viewer.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
            lore.add("<dark_gray>Shift-click: " + (lobby.isReleased(id) ? "freeze again" : "release from the freeze"));
        }
        return Icons.head(id, "<white>" + name, lore);
    }

    @Override
    protected void onClick(UUID id, InventoryClickEvent event) {
        if (event.isShiftClick() && viewer.hasPermission(PermissionNodes.LEMMEMOVE_OTHERS)) {
            if (!lobby.refreeze(id)) {
                lobby.release(id);
            }
        } else if (id.equals(viewer.getUniqueId()) || viewer.hasPermission(PermissionNodes.ADMIN)) {
            lobby.toggleSpectator(id);
        }
        refresh();
    }
}
