package de.raindancer.modules.manhunt.service;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The real server whitelist, through Bukkit's own API. */
final class BukkitWhitelistGateway implements WhitelistGateway {

    private final Server server;

    BukkitWhitelistGateway(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Collection<UUID> onlinePlayerIds() {
        List<UUID> ids = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }
        return List.copyOf(ids);
    }

    @Override
    public boolean isWhitelisted(UUID id) {
        return server.getOfflinePlayer(id).isWhitelisted();
    }

    @Override
    public void setWhitelisted(UUID id, boolean whitelisted) {
        OfflinePlayer offline = server.getOfflinePlayer(id);
        offline.setWhitelisted(whitelisted);
    }

    @Override
    public boolean isWhitelistEnabled() {
        return server.hasWhitelist();
    }

    @Override
    public void setWhitelistEnabled(boolean enabled) {
        server.setWhitelist(enabled);
    }
}
