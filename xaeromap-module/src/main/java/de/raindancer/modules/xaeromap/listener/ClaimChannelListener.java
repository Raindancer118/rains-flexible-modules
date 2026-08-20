package de.raindancer.modules.xaeromap.listener;

import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.service.ClaimSyncService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * What a client sends back on the claims channel.
 *
 * <p>Not a Bukkit {@code Listener} — an incoming plugin message arrives through the messenger rather
 * than the event bus, which is why this is the one thing in this package the module registers by hand
 * and unregisters itself. It still lives here because it is an event handler in every sense that
 * matters.
 *
 * <p>Exactly one message is acted on: the echo of the probe, which is how a server learns the player
 * actually has a map mod that reads this protocol. Everything else — the mod's requests to claim a
 * chunk, change a config, join a party — is ignored on purpose. Answering a claim request would be a
 * second way to make a claim on this server, one that knows nothing about who may claim, what it costs
 * or how large a claim may be.
 */
public final class ClaimChannelListener implements PluginMessageListener {

    private final ClaimSyncService claims;

    public ClaimChannelListener(ClaimSyncService claims) {
        this.claims = claims;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        byte @NotNull [] message) {
        if (!OpacPackets.CHANNEL.equals(channel)) {
            return;
        }
        claims.onClientMessage(player, message);
    }
}
