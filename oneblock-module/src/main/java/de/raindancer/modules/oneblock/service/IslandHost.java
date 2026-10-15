package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.IslandView;

import java.util.Optional;
import java.util.UUID;

/**
 * The islands, seen from the invites and the sidebar: the one seam to them, so both are tested without a server.
 * Called on the server thread only.
 */
public interface IslandHost {

    /** Whether the islands are loaded and can be asked. */
    boolean ready();

    /** The inviter's island and what decides an invite to it; empty when they have none. */
    Optional<IslandFacts> facts(UUID inviter, UUID invitee);

    /** A second record of a team invite, for an island host that keeps its own; nothing for one that does not. */
    void offerTeam(Invite invite);

    /** Takes that record back, if it is still this one. */
    void withdrawTeam(Invite invite);

    /** Puts the invited player, online here, on the inviter's team. */
    void join(Invite invite);

    /** Takes the player, online here, to a safe spot on that island. */
    void visit(UUID player, String islandId);

    /** Whether the player, online here, is in one of the game mode's worlds. */
    boolean inGameWorld(UUID player);

    /** The island the player, online here, stands on — or their own while they are elsewhere; empty with neither. */
    Optional<IslandView> view(UUID player);

    /** To their island, or to making one. */
    void home(UUID player);

    /**
     * Somebody arrived on the island server with nothing accepted to finish: back where they left off on an island, or
     * to their own — or to making one.
     */
    default void arrived(UUID player) {
        home(player);
    }
}
