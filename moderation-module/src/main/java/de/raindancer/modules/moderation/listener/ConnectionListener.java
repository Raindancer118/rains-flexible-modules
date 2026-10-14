package de.raindancer.modules.moderation.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.AntiCheatServices;
import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.Flag;
import de.raindancer.modules.moderation.model.PlayerTrack;
import de.raindancer.modules.moderation.util.CheatPermissions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.List;
import java.util.UUID;

/** Joining and leaving: a player's record starts and ends, and their client is held to the server's policy. */
public final class ConnectionListener implements IModerationListener {

    private final AntiCheatServices services;
    private final List<IModerationListener> everyone;

    public ConnectionListener(AntiCheatServices services, List<IModerationListener> everyone) {
        this.services = services;
        this.everyone = everyone;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerTrack track = services.tracks().of(player);
        refreshPermissions(player, track);
        services.alerts().joined(player);
        services.tap().inject(player);
        // Levels earned on another server of the network come along.
        Scheduling.async(services.plugin(), () -> services.violations().arrived(track));
        // The brand arrives during configuration, but some proxies forward it late.
        Scheduling.entityLater(services.plugin(), player, 40, () -> checkBrand(player, track));
    }

    /** What this player may skip: everything, or single families. Read again before each flag is weighed. */
    public static void refreshPermissions(Player player, PlayerTrack track) {
        track.bypassAll = player.hasPermission(CheatPermissions.BYPASS);
        for (CheckType check : CheckType.values()) {
            if (player.hasPermission(CheatPermissions.BYPASS_PREFIX + check.key())) {
                track.bypassed.add(check);
            } else {
                track.bypassed.remove(check);
            }
        }
        track.ping = player.getPing();
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
        for (IModerationListener listener : everyone) {
            listener.forget(id);
        }
        services.alerts().forget(id);
        services.violations().forget(id);
        Scheduling.async(services.plugin(), () -> services.violations().departed());
        services.shield().forget(id);
        services.tracks().forget(id);
    }

    /** Nothing kept per player here: the tracks, alerts and shield forget on their own, from {@link #onQuit}. */
    @Override
    public void forget(UUID player) {
    }

    @Override
    public String describe() {
        return "starting and ending each player's record, and checking their client";
    }
}
