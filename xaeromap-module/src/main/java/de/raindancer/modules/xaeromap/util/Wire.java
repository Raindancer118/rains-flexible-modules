package de.raindancer.modules.xaeromap.util;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * How bytes reach one player's client, and the one place that knows they must not be sent from
 * anywhere.
 *
 * <p>An interface rather than a call to {@code sendPluginMessage} where it is needed, for two reasons.
 * A test can hand the services one of these and read exactly what they emitted, which for a protocol
 * whose failure mode is "the client silently draws nothing" is the only way to check it at all. And on
 * Folia a player belongs to a region thread: sending from the timer thread is a cross-thread touch of
 * an entity, which is the class of bug that shows up as an occasional crash report rather than as
 * anything reproducible. {@link #through} is the one implementation that gets that right.
 */
public interface Wire {

    /** Sends one message, on whichever thread owns the player. */
    void send(Player player, String channel, byte[] message);

    /** The real thing: through the plugin, on the player's own thread. */
    static Wire through(Plugin plugin) {
        return (player, channel, message) -> {
            if (player == null || !player.isOnline()) {
                return;
            }
            Scheduling.entity(plugin, player, () -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(plugin, channel, message);
                }
            });
        };
    }
}
