package de.raindancer.modules.arena.api.wire;

import de.raindancer.core.RainsCore;
import org.bukkit.plugin.Plugin;

/** Picks the wire a server has: the network's when it is on one, the 1.x link otherwise. */
public final class Wires {

    private Wires() {
    }

    public static Wire of(RainsCore core, Plugin plugin) {
        if (core.network().isNetwork() && core.networkCalls().isPresent() && core.networkPlayers().isPresent()
                && core.networkTravel().isPresent()) {
            return NetworkWire.over(core.networkCalls().get(), core.networkPlayers().get(), core.networkTravel().get());
        }
        return new LinkWire(core::link, plugin, core::playersElsewhere);
    }
}
