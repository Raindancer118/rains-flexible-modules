package de.raindancer.modules.hungergames.model;

import de.raindancer.core.social.team.Team;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a round is, written down at one instant.
 *
 * <h2>Why the whole session fits in one record</h2>
 * Because a restart has to restore all of it or none of it. A round that comes back with its teams but
 * not its eliminations resumes a game where the dead are alive again; one with its eliminations but not
 * its running-since time cannot say how long is left. {@code store.SessionStore} writes this record after
 * every mutation and hands one back on load, so "does the session survive a restart, mid-round included"
 * — one of this module's invariants — is a property of this type being complete rather than of the code
 * that saves it remembering to save enough.
 *
 * <h2>Why {@link #teams()} is a plain list of Core's {@link Team}</h2>
 * Core's {@link de.raindancer.core.social.team.Teams} already carries a snapshot as exactly this shape —
 * see {@code Teams.snapshot()} and {@code Teams.restore(Collection)} — so a second, module-shaped row for
 * the same data would be a second place a team's fields could drift from what the roster actually holds.
 * This record stores what {@code Teams} hands out and hands back exactly that on restore.
 *
 * @param phase              where the round has got to
 * @param participants       every tribute, with their state, at the moment of the snapshot
 * @param teams              every team, exactly as {@code Teams.snapshot()} produced it
 * @param winner             how the round ended, or {@code null} while it is still undecided
 * @param kills              kill counts by killer
 * @param runningSinceMillis when {@link GamePhase#RUNNING} began, epoch milliseconds, or {@code null} if
 *                           it has not started yet
 */
public record SessionSnapshot(
        GamePhase phase,
        List<ParticipantData> participants,
        List<Team> teams,
        Winner winner,
        Map<UUID, Integer> kills,
        Long runningSinceMillis) {

    public SessionSnapshot {
        participants = List.copyOf(participants);
        teams = List.copyOf(teams);
        kills = Map.copyOf(kills);
    }

    /** One tribute, as it was saved. */
    public record ParticipantData(UUID uuid, String name, ParticipantState state) {
    }
}
