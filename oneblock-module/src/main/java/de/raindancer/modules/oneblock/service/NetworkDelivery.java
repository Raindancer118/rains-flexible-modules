package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.LinkDirectory;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Lines for a player here or on any other server — over the network's presence on a network, over the links
 * otherwise — and who a typed name is wherever they are.
 */
public final class NetworkDelivery implements InviteService.Delivery {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final Supplier<LinkDirectory> links;
    private final Supplier<Optional<NetworkPlayers>> network;
    private final Predicate<Player> hidden;

    public NetworkDelivery(Plugin plugin, Server server, Messages messages, Supplier<LinkDirectory> links,
                           Supplier<Optional<NetworkPlayers>> network, Predicate<Player> hidden) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.links = links;
        this.network = network;
        this.hidden = hidden;
    }

    @Override
    public void tell(String name, String key, String... values) {
        Optional<Player> here = here(name);
        if (here.isPresent()) {
            Player player = here.get();
            Scheduling.entity(plugin, player, () -> messages.send(player, key, (Object[]) values));
            return;
        }
        Optional<NetworkPlayers> players = network.get();
        if (players.isPresent()) {
            players.get().named(name, false).ifPresent(there -> players.get().tell(there.player(), key, values));
            return;
        }
        links.get().tell(name, key, values);
    }

    /** Here first, then elsewhere; a vanished player is nobody. */
    public CompletableFuture<Optional<LinkDirectory.Found>> resolve(String name) {
        Optional<Player> here = here(name).filter(player -> !hidden.test(player));
        if (here.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(
                    new LinkDirectory.Found(here.get().getUniqueId(), here.get().getName(), "")));
        }
        Optional<NetworkPlayers> players = network.get();
        if (players.isPresent()) {
            return CompletableFuture.completedFuture(players.get().named(name, false)
                    .map(there -> new LinkDirectory.Found(there.player(), there.name(), there.server())));
        }
        return links.get().whois(name);
    }

    private Optional<Player> here(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Player exact = server.getPlayerExact(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        return server.getOnlinePlayers().stream().filter(online -> online.getName().equalsIgnoreCase(name))
                .map(Player.class::cast).findFirst();
    }
}
