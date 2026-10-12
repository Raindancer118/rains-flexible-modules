package de.raindancer.modules.guilds.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.service.GuildNotifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/**
 * Coming and going: the message of the day and what is waiting for them on join; forgetting them on quit.
 * The greeting comes a few seconds after joining, so it is not lost under the server's own join lines.
 */
public final class PresenceListener implements IGuildsListener {

    private final GuildsServices services;
    private final List<IGuildsListener> others;

    /** @param others the module's other listeners, told to forget a player who leaves */
    public PresenceListener(GuildsServices services, List<IGuildsListener> others) {
        this.services = services;
        this.others = others;
    }

    @Override
    public void forget(UUID player) {
        services.homes().forget(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduling.entityLater(services.plugin(), player, 60L, () -> greet(player));
    }

    private void greet(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        services.guilds().guildOf(id).ifPresentOrElse(guild -> {
            if (!guild.motd().isBlank()) {
                services.messages().send(player, "guilds.motd", "guild", GuildNotifier.title(guild), "motd", guild.motd());
            }
            waitingFor(player, guild);
        }, () -> {
            int invitations = services.guilds().invitationsOf(id).size();
            if (invitations > 0) {
                services.messages().send(player, "guilds.invite.waiting", "count", invitations);
            }
        });
    }

    private void waitingFor(Player player, Guild guild) {
        if (guild.may(player.getUniqueId(), GuildPermission.INVITE)) {
            int applications = services.guilds().applicationsTo(guild).size();
            if (applications > 0) {
                services.messages().send(player, "guilds.apply.waiting", "count", applications);
            }
        }
        if (guild.may(player.getUniqueId(), GuildPermission.RELATIONS)) {
            int alliances = services.relations().alliancesAskedOf(guild).size();
            if (alliances > 0) {
                services.messages().send(player, "guilds.ally.waiting", "count", alliances);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        forget(player);
        for (IGuildsListener other : others) {
            other.forget(player);
        }
    }
}
