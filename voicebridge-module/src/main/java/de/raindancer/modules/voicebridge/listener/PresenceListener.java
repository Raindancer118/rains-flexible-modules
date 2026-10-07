package de.raindancer.modules.voicebridge.listener;

import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Coming and going: a player who joins while their Discord account waits in the lobby is carried in;
 * one who leaves takes their Discord line, decoder and queued audio with them.
 */
public final class PresenceListener implements IVoiceBridgeListener {

    private final VoiceBridgeServices services;

    public PresenceListener(VoiceBridgeServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        services.lobby().playerJoined(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.gateway().forget(player);
        services.lobby().playerLeft(player);
    }
}
