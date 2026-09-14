package de.raindancer.modules.manhunt.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compass target each Hunter was last sent, so the same one is not sent again.
 *
 * <p>Found with two real clients on a local server: every Hunter received the same compass target
 * packet twice a second, on every sweep, whether the Runner had moved or not. Nothing a player sees,
 * but a packet per Hunter per sweep for no change at all. Compared by block and world, the same
 * resolution the needle is aimed at.
 */
final class CompassTargets {

    private record Target(String world, int x, int y, int z) {
    }

    private final Map<UUID, Target> sent = new ConcurrentHashMap<>();

    /** Whether this target differs from the last one sent to {@code hunter} — and remembers it if so. */
    boolean moved(UUID hunter, String world, int x, int y, int z) {
        Target now = new Target(world, x, y, z);
        return !now.equals(sent.put(hunter, now));
    }

    void forget(UUID hunter) {
        sent.remove(hunter);
    }

    void clear() {
        sent.clear();
    }
}
