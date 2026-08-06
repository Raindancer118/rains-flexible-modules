package de.raindancer.modules.hungergames.model;

import de.raindancer.core.social.team.TeamId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * How a round ended.
 *
 * <h2>Why a sealed interface and not a nullable winner</h2>
 * Because there are three outcomes and only two of them are a victory. A round can end with nobody left —
 * the last two tributes kill each other, the time runs out with the arena empty, an admin ends it — and that
 * is a real result, not a missing one. Expressed as a nullable {@code UUID}, "nobody won" and "we have not
 * worked it out yet" are the same value, and the announcement that goes out is whichever of those two the
 * caller assumed.
 *
 * <p>Sealed, so the three cases can be switched over exhaustively: adding a fourth kind of ending would fail
 * to compile everywhere that has to say something about it, which is exactly where a new ending needs to be
 * thought about.
 */
public sealed interface Winner {

    /** One tribute was the last standing. */
    record Solo(UUID uuid) implements Winner {
        public Solo {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    /**
     * A team won.
     *
     * @param teamId  the team
     * @param members everybody who was on it when it won — including the ones who did not survive to see it,
     *                because they won too and the announcement names them
     */
    record Team(TeamId teamId, Set<UUID> members) implements Winner {
        public Team {
            Objects.requireNonNull(teamId, "teamId");
            members = Set.copyOf(members);
        }
    }

    /** It ended and nobody won. A result, not the absence of one — see the class note. */
    record None() implements Winner {
    }
}
