package de.raindancer.modules.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Names are looked up through {@code PlayerTargets}, which asks {@code Bukkit.getServer()}; with
 * {@code Bukkit} statically mocked that is null unless a test hands it one. Not a test itself.
 */
public final class BukkitServerStub {

    private BukkitServerStub() {
    }

    /** Makes {@code name} answer to {@code player} (null: nobody) on the statically mocked Bukkit. */
    public static void online(MockedStatic<Bukkit> bukkit, String name, Player player) {
        Server server = Bukkit.getServer();
        if (server == null) {
            server = mock(Server.class);
            Server installed = server;
            bukkit.when(Bukkit::getServer).thenReturn(installed);
        }
        when(server.getPlayerExact(name)).thenReturn(player);
    }
}
