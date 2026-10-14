package de.raindancer.modules.twitch.listener;

import de.raindancer.modules.twitch.service.TwitchAdvancements;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Joins, leaves and deaths, for the Twitch advancements. */
public final class TwitchWatchers implements Listener {

    private final TwitchAdvancements advancements;

    public TwitchWatchers(TwitchAdvancements advancements) {
        this.advancements = advancements;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        advancements.joined(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        advancements.quit(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        advancements.died(event.getPlayer());
    }
}
