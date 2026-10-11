package de.raindancer.modules.sends.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.sends.SendsServices;
import de.raindancer.modules.sends.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * At login: whatever came back to somebody while they were away is given to them, and they are told what is
 * waiting for them to accept.
 *
 * <p>A little after the join rather than in it, so the lines are not buried under the server's own greeting.
 */
public final class SendJoinListener implements ISendsListener {

    private static final long LATER_TICKS = 60L;

    private final SendsServices services;

    public SendJoinListener(SendsServices services) {
        this.services = services;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduling.entityLater(services.plugin(), player, LATER_TICKS, () -> {
            if (!player.isOnline() || !player.hasPermission(PermissionNodes.USE)) {
                return;
            }
            services.sends().collectAll(player, services.config().remindOnJoin());
            int waiting = services.book().incoming(player.getUniqueId()).size();
            if (waiting > 0 && services.config().remindOnJoin()) {
                services.messages().send(player, "sends.reminder", "count", String.valueOf(waiting));
            }
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player here: what is waiting lives in the book.
    }
}
