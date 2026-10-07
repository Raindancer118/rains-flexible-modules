package de.raindancer.modules.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
        if (player != null) {
            when(player.isOnline()).thenReturn(true);
        }
    }

    /** Makes {@code name} a real name the server has seen but who is not here. */
    public static OfflinePlayer offline(MockedStatic<Bukkit> bukkit, String name, java.util.UUID id) {
        online(bukkit, name, null);
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(away.getName()).thenReturn(name);
        when(away.getUniqueId()).thenReturn(id);
        when(away.isOnline()).thenReturn(false);
        when(Bukkit.getServer().getOfflinePlayerIfCached(name)).thenReturn(away);
        return away;
    }

    /** Makes the selector {@code text} match {@code who} for {@code sender}, and lets the sender use selectors. */
    public static void selector(MockedStatic<Bukkit> bukkit, org.bukkit.command.CommandSender sender, String text,
                                Player... who) {
        online(bukkit, "unused", null);
        when(sender.hasPermission("minecraft.command.selector")).thenReturn(true);
        when(Bukkit.getServer().selectEntities(sender, text)).thenReturn(java.util.List.of(who));
    }
}
