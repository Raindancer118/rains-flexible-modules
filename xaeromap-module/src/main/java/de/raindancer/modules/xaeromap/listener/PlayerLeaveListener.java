package de.raindancer.modules.xaeromap.listener;

import de.raindancer.modules.xaeromap.service.ClaimSyncService;
import de.raindancer.modules.xaeromap.store.MapClients;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Drops what was being remembered about a player who has gone.
 *
 * <p>What is remembered is not small — for every player, which claims and which chunks their client has
 * been told about — so a server that never forgets grows by a map's worth of state per player who has
 * ever joined. Two listeners in this repository have had exactly that bug.
 */
public final class PlayerLeaveListener implements IXaeroMapListener {

    private final ClaimSyncService claims;
    private final MapClients clients;

    public PlayerLeaveListener(ClaimSyncService claims, MapClients clients) {
        this.claims = claims;
        this.clients = clients;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        claims.forget(player);
        clients.forget(player);
    }
}
