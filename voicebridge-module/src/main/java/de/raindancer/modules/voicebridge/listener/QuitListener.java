package de.raindancer.modules.voicebridge.listener;

import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Lets go of somebody's decoder and queued audio when they leave. Simple Voice Chat says so too,
 * but only for players whose voice chat had connected — this covers the rest.
 */
public final class QuitListener implements IVoiceBridgeListener {

    private final VoiceBridgeServices services;

    public QuitListener(VoiceBridgeServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.gateway().forget(player);
    }
}
