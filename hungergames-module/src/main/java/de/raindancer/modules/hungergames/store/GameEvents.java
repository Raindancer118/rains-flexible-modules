package de.raindancer.modules.hungergames.store;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;

import java.util.UUID;

/**
 * The port through which the game logic announces what just happened.
 *
 * <p>A Bukkit implementation fires the module's Bukkit events on the main thread, so other plugins can
 * listen for a Hunger Games round the same way they listen for anything else on the server; a test uses a
 * recording stub. Either way, {@code store.GameSession} itself stays free of any Bukkit dependency — it
 * calls these methods and does not know or care what happens after.
 */
public interface GameEvents {

    void phaseChanged(GamePhase oldPhase, GamePhase newPhase);

    /** @param killer {@code null} for an environmental or admin elimination */
    void participantEliminated(UUID participant, UUID killer, int remainingAlive);

    void participantRevived(UUID participant);

    void whitelistChanged(UUID player, boolean added);

    void teamCreated(Team team);

    void teamDeleted(Team team);

    void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour);

    /**
     * @param oldTeam {@code null} if the player had no team before
     * @param newTeam {@code null} if the player has left their team
     */
    void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause);

    void kill(UUID killer, UUID victim, int killerTotalKills);

    void winnerDeclared(Winner winner);

    /** What caused a team membership change, mirrored into the Bukkit event. */
    enum MembershipCause {
        API,
        ADMIN,
        PLAYER,
        RANDOM
    }
}
