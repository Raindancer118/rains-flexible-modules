package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.model.XaeroWorldId;
import de.raindancer.modules.xaeromap.util.Wire;
import org.bukkit.entity.Player;

/**
 * Tells a client which world it is in, in the one form Xaero's two map mods read.
 *
 * <p>Sent on three occasions, and all three are needed: when the client says it is listening (a join
 * where the mod registers its channel after the login packets have gone), when the player changes
 * world, and when a resync is asked for by hand. A join alone is not enough — the mod is frequently not
 * listening yet at that point, and the packet is dropped with no sign of it on either side.
 */
public final class WorldIdService implements IXaeroMapService {

    private final Wire wire;
    private volatile XaeroMapSettings settings;

    public WorldIdService(Wire wire, XaeroMapSettings settings) {
        this.wire = wire;
        this.settings = settings;
    }

    @Override
    public void settings(XaeroMapSettings settings) {
        this.settings = settings;
    }

    /** Both channels, because a client may have the minimap, the world map, or both. */
    public void send(Player player) {
        if (!settings.worldIds() || player == null) {
            return;
        }
        byte[] packet = XaeroWorldId.packet(XaeroWorldId.of(player.getWorld().getUID()));
        wire.send(player, XaeroWorldId.MINIMAP_CHANNEL, packet);
        wire.send(player, XaeroWorldId.WORLDMAP_CHANNEL, packet);
    }

    /** The same, for one channel only — which is what a channel-registration event knows about. */
    public void send(Player player, String channel) {
        if (!settings.worldIds() || player == null) {
            return;
        }
        wire.send(player, channel, XaeroWorldId.packet(XaeroWorldId.of(player.getWorld().getUID())));
    }
}
