package de.raindancer.modules.arena.util;

import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import org.bukkit.Server;

/** Who is on this server now, for Core's player chooser — asking somebody to a fight only makes sense for them. */
public final class OnlinePlayers {

    private OnlinePlayers() {
    }

    public static PlayerDirectory of(Server server) {
        return new PlayerDirectory(() -> server.getOnlinePlayers().stream()
                .map(player -> new PlayerEntry(player.getUniqueId(), player.getName(), true, System.currentTimeMillis()))
                .toList(), System::currentTimeMillis);
    }
}
