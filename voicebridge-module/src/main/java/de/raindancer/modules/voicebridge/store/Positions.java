package de.raindancer.modules.voicebridge.store;

import de.raindancer.modules.voicebridge.util.Spatial;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every online player's head is, refreshed every couple of ticks on each player's own thread,
 * so the audio threads can place voices without touching the world.
 */
public final class Positions {

    private final Map<UUID, Spatial.Ear> ears = new ConcurrentHashMap<>();

    public void put(UUID player, Spatial.Ear ear) {
        ears.put(player, ear);
    }

    public Optional<Spatial.Ear> of(UUID player) {
        return Optional.ofNullable(ears.get(player));
    }

    public void forget(UUID player) {
        ears.remove(player);
    }
}
