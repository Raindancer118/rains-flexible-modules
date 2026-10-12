package de.raindancer.modules.arenagate.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What a player agreed to: a stake of up to so much, a fight in their own gear. Asked before a stake is taken or a copy of
 * their gear handed out, and used up by it — so nothing is taken from anybody, and nobody's inventory read out, merely
 * because the arena named them. Agreements come from what a player does here ({@code /pvp queue}, an invite, an accept)
 * or from the arena as they queue or accept there; each lasts {@link #LIFETIME}.
 */
public final class GateConsents {

    public static final long LIFETIME = 15 * 60 * 1000L;

    private record Agreed(long stake, boolean gear, long expires) {
    }

    private final Map<UUID, Agreed> agreed = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public GateConsents(LongSupplier clock) {
        this.clock = clock;
    }

    public void agree(UUID player, long stake, boolean gear) {
        long expires = clock.getAsLong() + LIFETIME;
        agreed.merge(player, new Agreed(Math.max(0, stake), gear, expires),
                (old, fresh) -> new Agreed(Math.max(old.stake(), fresh.stake()), old.gear() || fresh.gear(), expires));
    }

    /** @return whether they agreed to at least this stake; the agreement to a stake is used up */
    public synchronized boolean takeStake(UUID player, long amount) {
        Agreed found = live(player);
        if (found == null || found.stake() < amount) {
            return false;
        }
        agreed.put(player, new Agreed(0, found.gear(), found.expires()));
        return true;
    }

    /** @return whether they chose to fight in their own gear; used up */
    public synchronized boolean takeGear(UUID player) {
        Agreed found = live(player);
        if (found == null || !found.gear()) {
            return false;
        }
        agreed.put(player, new Agreed(found.stake(), false, found.expires()));
        return true;
    }

    private Agreed live(UUID player) {
        Agreed found = agreed.get(player);
        if (found == null || found.expires() < clock.getAsLong()) {
            agreed.remove(player);
            return null;
        }
        return found;
    }
}
