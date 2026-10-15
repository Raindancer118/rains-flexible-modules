package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.Island;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where islands are kept. Blocking: never on a thread that runs the world. */
public interface IslandStorage {

    /** Every island still played; empty when the store could not be read, which is not "there are none". */
    Optional<List<Island>> loadAll();

    /** The island and its member index, in one transaction. */
    boolean save(Island island);

    /** Leaves the row and its worlds, off the member index: nobody plays it any more, nothing is deleted. */
    boolean archive(Island island);

    boolean savePosition(java.util.UUID player, PlayerSpot spot, long now);

    Optional<PlayerSpot> position(UUID player);

    /** For servers other than the island server: the money the island of this player plays with, if any. */
    Optional<String> modeOf(UUID player);

    /**
     * The same for many players in one query — the tablist asks for everybody online at once.
     *
     * @return empty when the store could not be read; players without an island are left out of the map
     */
    Optional<java.util.Map<UUID, String>> modesOf(java.util.Collection<UUID> players);
}
