package de.raindancer.modules.hungergames.model;

import de.raindancer.core.social.team.TeamId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One tribute, as they are right now.
 *
 * <h2>The UUID is the tribute; the name is a caption</h2>
 * {@link #uuid()} is the only identity. {@link #lastKnownName()} is the last username anybody saw and it is
 * allowed to be out of date — somebody who renames their Mojang account mid-tournament is still the same
 * tribute, still on the same team, still holding the same kills. Anything that keys off the name instead
 * loses them at exactly the moment nobody is watching, which is the middle of a round.
 *
 * <p>Which is also why the name is stored rather than looked up. A tribute who is offline has no
 * {@code Player} to ask, and a scoreboard, a spectator compass and a whitelist screen all want to show a name
 * for somebody who is not there. Storing the last one means a caption that may be stale; looking it up would
 * mean a blank, or a lookup on the main thread.
 *
 * <h2>Immutable, and copied rather than changed</h2>
 * Every change makes a new one. That is what lets the registry hand a participant out to a screen, a rule and
 * a scoreboard at the same time on three different threads without any of them seeing a half-applied change —
 * somebody eliminated and not yet off their team, for instance, which is a state that would end a round.
 *
 * @param uuid          their Mojang UUID, and the only thing that identifies them
 * @param lastKnownName the last username seen, never null, allowed to be stale
 * @param state         still in, or out
 * @param teamId        the team they are on, if any
 */
public record Participant(
        UUID uuid,
        String lastKnownName,
        ParticipantState state,
        Optional<TeamId> teamId) {

    public Participant {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lastKnownName, "lastKnownName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(teamId, "teamId");
    }

    /** Whether they are still in the game — which says nothing about whether they are online. */
    public boolean isAlive() {
        return state == ParticipantState.ALIVE;
    }

    /** The same tribute, in or out. */
    public Participant withState(ParticipantState newState) {
        return new Participant(uuid, lastKnownName, newState, teamId);
    }

    /** The same tribute, on that team — or on none, which is what empty means. */
    public Participant withTeam(Optional<TeamId> newTeam) {
        return new Participant(uuid, lastKnownName, state, newTeam);
    }

    /** The same tribute, with the caption brought up to date. */
    public Participant withName(String name) {
        return new Participant(uuid, name, state, teamId);
    }
}
