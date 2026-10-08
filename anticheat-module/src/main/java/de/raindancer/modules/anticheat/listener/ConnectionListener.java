package de.raindancer.modules.anticheat.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.UUID;

/** Joining, leaving, respawning, teleporting: everything that starts a player's record afresh. */
public final class ConnectionListener implements IAntiCheatListener {

    private final AntiCheatServices services;
    private final List<IAntiCheatListener> everyone;

    public ConnectionListener(AntiCheatServices services, List<IAntiCheatListener> everyone) {
        this.services = services;
        this.everyone = everyone;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerTrack track = services.tracks().of(player);
        track.entityId = player.getEntityId();
        track.timer.restart();
        track.exempt(PlayerTrack.Exemption.JOINED, 3000);
        services.alerts().joined(player);
        services.tap().inject(player);
        services.engine().start(player);
        // The brand arrives during configuration, but some proxies forward it late.
        Scheduling.entityLater(services.plugin(), player, 40, () -> checkBrand(player, track));
    }

    private void checkBrand(Player player, PlayerTrack track) {
        AntiCheatSettings settings = services.config();
        String brand = player.getClientBrandName();
        if (brand == null) {
            return;
        }
        if (settings.announceBrands()) {
            services.alerts().staff("anticheat.brand", "player", player.getName(), "brand", brand);
        }
        String refused = AntiCheatSettings.matching(settings.blockedBrands(), brand);
        if (refused != null) {
            refuse(player, track, "client brand '" + brand + "'", brand);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannel(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        String refused = AntiCheatSettings.matching(services.config().blockedChannels(), event.getChannel());
        if (refused != null) {
            refuse(player, services.tracks().of(player), "mod channel '" + event.getChannel() + "'", event.getChannel());
        }
    }

    private void refuse(Player player, PlayerTrack track, String detail, String what) {
        if (!services.violations().runs(track, CheckType.CLIENT)) {
            return;
        }
        Scheduling.entity(services.plugin(), player, () -> services.violations().flag(player, track, Flag.of(CheckType.CLIENT, detail)));
        if (services.config().kickBlockedClients()) {
            services.alerts().staff("anticheat.client-refused", "player", player.getName(), "client", what);
            services.punishments().refuseClient(player, what);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        services.tap().eject(event.getPlayer());
        for (IAntiCheatListener listener : everyone) {
            listener.forget(id);
        }
        services.alerts().forget(id);
        services.violations().forget(id);
        services.shield().forget(id);
        services.tracks().forget(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        synchronized (track) {
            track.movement.teleportTarget = event.getTo().clone();
            track.movement.teleportAtMillis = track.now();
            track.movement.velocities.clear();
            track.timer.reset();
        }
        track.exempt(PlayerTrack.Exemption.TELEPORTED, 200);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        track.entityId = event.getPlayer().getEntityId();
        synchronized (track) {
            track.movement.forget();
            track.movement.known = false;
            track.timer.restart();
        }
        track.exempt(PlayerTrack.Exemption.RESPAWNED, 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        synchronized (track) {
            track.movement.known = false;
            track.timer.restart();
        }
        track.exempt(PlayerTrack.Exemption.WORLD_CHANGED, 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        services.tracks().of(event.getPlayer()).exempt(PlayerTrack.Exemption.GAME_MODE, 1500);
    }

    @Override
    public String describe() {
        return "starting and ending each player's record, and checking their client";
    }
}
