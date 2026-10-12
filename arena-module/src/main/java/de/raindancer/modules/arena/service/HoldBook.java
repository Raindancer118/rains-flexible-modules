package de.raindancer.modules.arena.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Who is waiting in the hub while survival restarts for an update — to be sent back the moment it is up again, and only
 * them: somebody who came to fight stays.
 */
public final class HoldBook {

    private final Set<UUID> held = ConcurrentHashMap.newKeySet();

    public void hold(UUID player) {
        held.add(player);
    }

    public boolean isHeld(UUID player) {
        return held.contains(player);
    }

    public void release(UUID player) {
        held.remove(player);
    }

    public synchronized List<UUID> takeAll() {
        return takeAllBut(player -> false);
    }

    /** Everybody held but those {@code stays} says to keep — they are kept held for later. */
    public synchronized List<UUID> takeAllBut(Predicate<UUID> stays) {
        List<UUID> leaving = held.stream().filter(stays.negate()).toList();
        leaving.forEach(held::remove);
        return leaving;
    }
}
