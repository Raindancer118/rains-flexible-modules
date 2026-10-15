package de.raindancer.modules.oneblock.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.service.IslandServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Portals on an island lead to the same island's other dimensions — a nether portal between its overworld and its
 * nether, an end portal into its end and back out — never to the server's own nether or end. A dimension that is not
 * unlocked yet is said, not entered. Items and mobs stay on their island.
 */
public final class PortalListener implements IOneBlockListener {

    private final Plugin plugin;
    private final IslandServer islands;
    private final Messages messages;

    public PortalListener(Plugin plugin, IslandServer islands, Messages messages) {
        this.plugin = plugin;
        this.islands = islands;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        String world = event.getFrom().getWorld().getName();
        Optional<Island> island = islands.book().ofWorld(world);
        Optional<IslandDimension> from = Island.dimensionOf(world);
        if (island.isEmpty() || from.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Optional<IslandDimension> to = destination(from.get(), event.getCause());
        if (to.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (!island.get().unlocked(to.get())) {
            messages.send(player, "oneblock.portal.locked", "dimension", messages.get(to.get().nameKey()));
            return;
        }
        // A tick later: teleporting from inside the portal event fights the portal logic still running.
        Scheduling.entity(plugin, player, () -> islands.travel().go(player, island.get(), to.get()));
    }

    static Optional<IslandDimension> destination(IslandDimension from, PlayerTeleportEvent.TeleportCause cause) {
        return switch (cause) {
            case NETHER_PORTAL -> Optional.of(from == IslandDimension.NETHER ? IslandDimension.OVERWORLD
                    : IslandDimension.NETHER);
            case END_PORTAL, END_GATEWAY -> Optional.of(from == IslandDimension.END ? IslandDimension.OVERWORLD
                    : IslandDimension.END);
            default -> Optional.empty();
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (Island.isIslandWorld(event.getFrom().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    /** Nothing kept per player. */
    @Override
    public void forget(UUID player) {
    }
}
