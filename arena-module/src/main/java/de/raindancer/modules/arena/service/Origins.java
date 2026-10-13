package de.raindancer.modules.arena.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where somebody came from, so leaving sends them back there. Survival tells the arena ahead of whoever it sends; whoever
 * arrives unannounced came from the hub — or has no past here, and the hub is the network's front door.
 */
public final class Origins {

    /** The proxy's name for the hub server. */
    public static final String HUB = "hub";

    private final Map<UUID, Boolean> fromSurvival = new ConcurrentHashMap<>();

    public void arrived(UUID player, boolean announcedBySurvival) {
        fromSurvival.put(player, announcedBySurvival);
    }

    public String backTo(UUID player, String survivalServer) {
        return Boolean.TRUE.equals(fromSurvival.get(player)) ? survivalServer : HUB;
    }

    public boolean cameFromSurvival(UUID player) {
        return Boolean.TRUE.equals(fromSurvival.get(player));
    }

    public void forget(UUID player) {
        fromSurvival.remove(player);
    }
}
