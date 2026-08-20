package de.raindancer.modules.xaeromap.listener;

import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.model.XaeroWorldId;
import de.raindancer.modules.xaeromap.service.ClaimSyncService;
import de.raindancer.modules.xaeromap.service.WorldIdService;
import de.raindancer.modules.xaeromap.store.MapClients;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.UUID;

/**
 * The one moment a client can be spoken to at all.
 *
 * <h2>Why not the join event</h2>
 * A client's mods register their plugin-message channels when they feel like it, which for both of the
 * mods this module talks to is <em>after</em> the join event has already fired. A packet sent to a
 * channel the client has not registered is dropped by the client with no error on either side, so a
 * plugin that sends on join looks correct, logs nothing, and does nothing — the classic version of this
 * bug. {@code PlayerRegisterChannelEvent} is the client saying "I am listening now", and it is the only
 * honest place to answer from.
 *
 * <p>It fires once per channel, which is why each channel is answered on its own rather than all of them
 * on the first one that arrives.
 */
public final class ChannelListener implements IXaeroMapListener {

    private final WorldIdService worldIds;
    private final ClaimSyncService claims;
    private final MapClients clients;

    public ChannelListener(WorldIdService worldIds, ClaimSyncService claims, MapClients clients) {
        this.worldIds = worldIds;
        this.claims = claims;
        this.clients = clients;
    }

    @EventHandler
    public void onChannelRegistered(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        String channel = event.getChannel();
        if (XaeroWorldId.MINIMAP_CHANNEL.equals(channel) || XaeroWorldId.WORLDMAP_CHANNEL.equals(channel)) {
            // Remembered, not only answered: this is the one signal that the player has a map mod at
            // all, and a waypoint offer sent to somebody without one arrives as raw text.
            clients.found(player.getUniqueId());
            worldIds.send(player, channel);
            return;
        }
        if (OpacPackets.CHANNEL.equals(channel)) {
            claims.offer(player);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is kept here: what the client has been told lives in the sync service's own mirror,
        // and that is forgotten by PlayerLeaveListener.
    }
}
