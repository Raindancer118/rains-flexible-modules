package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.IslandFacts;

import java.util.Optional;
import java.util.UUID;

/**
 * The island plugin, seen from this module: the one seam to BentoBox, so everything else is tested without it.
 * Called on the server thread only.
 */
public interface IslandHost {

    /** Whether the game mode is loaded — false before BentoBox has finished starting, or without it. */
    boolean ready();

    /** The inviter's island and what decides an invite to it; empty when they have none. */
    Optional<IslandFacts> facts(UUID inviter, UUID invitee);

    /** BentoBox's own team invite, so its accept command — confirmation, team limits, leaving an old island — runs it. */
    void offerTeam(Invite invite);

    /** Takes BentoBox's invite back, if it is still this one. */
    void withdrawTeam(Invite invite);

    /** Runs BentoBox's accept for the invited player, who is online here. */
    void join(Invite invite);

    /** Takes the player, online here, to a safe spot on that island. */
    void visit(UUID player, String islandId);

    /** Whether the player, online here, is in one of the game mode's worlds. */
    boolean inGameWorld(UUID player);

    /** The game mode's own command: their island, or a new one. */
    void home(UUID player);
}
