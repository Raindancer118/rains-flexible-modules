package de.raindancer.modules.roles.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.roles.RolesServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** A nudge for whoever has no role yet, and staff's bypass dropped when they leave. */
public final class RoleListener implements IRolesListener {

    /** Long enough for the join messages to have scrolled past. */
    private static final long REMIND_AFTER_TICKS = 20L * 8;

    private final RolesServices services;

    public RoleListener(RolesServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!services.settings().get().remindOnJoin() || services.roles().roles().isEmpty()
                || services.roles().choiceOf(player.getUniqueId()).isPresent()) {
            return;
        }
        Scheduling.entityLater(services.plugin(), player, REMIND_AFTER_TICKS, () -> {
            if (player.isOnline() && services.roles().choiceOf(player.getUniqueId()).isEmpty()) {
                services.messages().send(player, "roles.no-role-yet");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.roles().forget(player);
    }
}
