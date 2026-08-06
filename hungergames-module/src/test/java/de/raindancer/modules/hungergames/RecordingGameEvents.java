package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.store.GameEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records every event as a string, for assertions in logic tests. */
final class RecordingGameEvents implements GameEvents {

    final List<String> events = new ArrayList<>();
    final List<Winner> winners = new ArrayList<>();

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        events.add("phase:" + oldPhase + "->" + newPhase);
    }

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        events.add("eliminated:" + participant + ":remaining=" + remainingAlive);
    }

    @Override
    public void participantRevived(UUID participant) {
        events.add("revived:" + participant);
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
        events.add("whitelist:" + player + ":" + (added ? "added" : "removed"));
    }

    @Override
    public void teamCreated(Team team) {
        events.add("teamCreated:" + team.id());
    }

    @Override
    public void teamDeleted(Team team) {
        events.add("teamDeleted:" + team.id());
    }

    @Override
    public void teamColourChanged(Team team, TeamColour oldColor, TeamColour newColor) {
        events.add("teamColor:" + team.id() + ":" + oldColor + "->" + newColor);
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
        events.add("membership:" + player + ":" + oldTeam + "->" + newTeam + ":" + cause);
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
        events.add("kill:" + killer + "->" + victim + ":total=" + killerTotalKills);
    }

    @Override
    public void winnerDeclared(Winner winner) {
        events.add("winner:" + winner);
        winners.add(winner);
    }
}
