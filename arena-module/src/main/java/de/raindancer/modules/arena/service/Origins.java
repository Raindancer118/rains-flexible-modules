package de.raindancer.modules.arena.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where somebody came from, so leaving sends them back there. A survival server tells the arena ahead of whoever it
 * sends; whoever arrives unannounced came from the hub — or has no past here, and the hub is the network's front door.
 * With several survival servers it is the one that sent them, by its id.
 */
public final class Origins {

    /** The proxy's name for the hub server. */
    public static final String HUB = "hub";

    /** @param home the survival server's id; empty on a link, which has only one */
    private record Origin(String home) {
    }

    private final Map<UUID, Origin> fromSurvival = new ConcurrentHashMap<>();

    public void arrived(UUID player, boolean announcedBySurvival) {
        arrived(player, announcedBySurvival, "");
    }

    public void arrived(UUID player, boolean announcedBySurvival, String home) {
        if (announcedBySurvival) {
            fromSurvival.put(player, new Origin(home == null ? "" : home));
        } else {
            fromSurvival.remove(player);
        }
    }

    public String backTo(UUID player, String survivalServer) {
        Origin origin = fromSurvival.get(player);
        if (origin == null) {
            return HUB;
        }
        return origin.home().isEmpty() ? survivalServer : origin.home();
    }

    public boolean cameFromSurvival(UUID player) {
        return fromSurvival.containsKey(player);
    }

    /** The survival server that sent them; empty on a link and for whoever nobody sent. */
    public Optional<String> homeOf(UUID player) {
        Origin origin = fromSurvival.get(player);
        return origin == null ? Optional.empty() : Optional.of(origin.home());
    }

    public void forget(UUID player) {
        fromSurvival.remove(player);
    }
}
