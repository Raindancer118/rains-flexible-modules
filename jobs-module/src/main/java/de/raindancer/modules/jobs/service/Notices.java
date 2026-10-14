package de.raindancer.modules.jobs.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Telling players things wherever they are: a payout is the winner's server's business, but the player it is for
 * may be on any server of the network. Placeholder values travel as text.
 */
public final class Notices {

    private static final String TOPIC = "jobs-announce";
    private static final char SEPARATOR = '\u001F';

    private final Server server;
    private final Messages messages;
    private volatile NetworkBus bus = NetworkBus.NONE;
    private volatile Supplier<Optional<NetworkPlayers>> players = Optional::empty;

    public Notices(Server server, Messages messages) {
        this.server = server;
        this.messages = messages;
    }

    /** Reaches players on the network's other servers too, and says what the other servers announce. Close to stop. */
    public NetworkBus.Subscription network(NetworkBus network, Supplier<Optional<NetworkPlayers>> everyone) {
        this.bus = network == null ? NetworkBus.NONE : network;
        this.players = everyone == null ? Optional::empty : everyone;
        return bus.subscribe(TOPIC, event -> {
            String[] parts = event.payload().split(String.valueOf(SEPARATOR), -1);
            Object[] values = Arrays.copyOfRange(parts, 1, parts.length);
            server.getOnlinePlayers().forEach(player -> messages.send(player, parts[0], values));
        });
    }

    /** Says it to the player where they are, on this server or another; nothing when they are nowhere. */
    public void tell(UUID player, String key, Object... values) {
        Player here = server.getPlayer(player);
        if (here != null) {
            messages.send(here, key, values);
            return;
        }
        players.get().ifPresent(network -> network.tell(player, key,
                Arrays.stream(values).map(String::valueOf).toArray(String[]::new)));
    }

    /** Says it to everybody on this server and on the network's other servers. */
    public void everybody(String key, Object... values) {
        server.getOnlinePlayers().forEach(player -> messages.send(player, key, values));
        StringBuilder payload = new StringBuilder(key);
        for (Object value : values) {
            payload.append(SEPARATOR).append(String.valueOf(value).replace(SEPARATOR, ' '));
        }
        bus.publish(TOPIC, key, payload.toString());
    }
}
