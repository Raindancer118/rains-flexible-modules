package de.raindancer.modules.xaeromap.store;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is actually running one of Xaero's map mods.
 *
 * <h2>Why this has to be known before anything is said</h2>
 * A waypoint offer is a chat message consisting of nothing but {@code xaero-waypoint:…}. A client with
 * the mod replaces it with a button; a client without it shows the raw line, exactly as typed, to
 * somebody who has no idea what it is. So the offer goes only to players whose client has registered
 * one of the mods' own plugin channels — which is the only honest signal a server gets, and the same
 * one the per-world map already waits for.
 *
 * <p>Deliberately separate from the claim sync's own idea of "ready": that one means the client also
 * speaks Open Parties and Claims, which is a different mod and a different question. A player with the
 * minimap and no OPAC gets waypoints and no claims, which is exactly right.
 */
public final class MapClients {

    private final Set<UUID> withAMapMod = ConcurrentHashMap.newKeySet();

    /** That player's client just registered one of the map channels. */
    public void found(UUID player) {
        if (player != null) {
            withAMapMod.add(player);
        }
    }

    public boolean hasAMapMod(UUID player) {
        return player != null && withAMapMod.contains(player);
    }

    public int count() {
        return withAMapMod.size();
    }

    /** They left. A set that is never cleaned grows by one for every player who has ever joined. */
    public void forget(UUID player) {
        withAMapMod.remove(player);
    }

    public void forgetEverybody() {
        withAMapMod.clear();
    }
}
