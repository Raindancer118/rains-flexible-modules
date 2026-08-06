package de.raindancer.modules.hungergames.rules;

import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.Winner;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure winner determination — no Bukkit, no state.
 *
 * <h2>Online status plays no part</h2>
 * A tribute who has disconnected stays alive and can therefore still stand in the way of a winner being
 * declared. That is deliberate — see {@code model.ParticipantState}'s class note — and it is the reason
 * this rule takes {@code Collection<Participant>} rather than anything that could distinguish a connected
 * tribute from one who is not: the distinction does not exist here, on purpose.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>More than one team still has somebody alive, or a teamless survivor stands next to other
 *       survivors → no winner yet.</li>
 *   <li>Every survivor belongs to the same team → that team wins.</li>
 *   <li>Exactly one survivor: their team wins if they have one — deliberately different from the source
 *       plugin's earlier behaviour, which always reported a lone survivor as a solo win even when they
 *       had teammates who had already fallen — otherwise it is a solo win.</li>
 *   <li>Nobody left alive → {@link Winner.None}.</li>
 * </ul>
 */
public final class WinnerRule implements IHungerGamesRule {

    /**
     * Whether the current roster has produced a winner.
     *
     * @param participants every tribute in the round, with their team
     * @return the winner, or empty while the round continues
     */
    public Optional<Winner> resolve(Collection<Participant> participants) {
        List<Participant> alive = participants.stream()
                .filter(Participant::isAlive)
                .toList();

        if (alive.isEmpty()) {
            return Optional.of(new Winner.None());
        }

        if (alive.size() == 1) {
            Participant last = alive.get(0);
            return Optional.of(last.teamId()
                    .<Winner>map(teamId -> teamWin(teamId, participants))
                    .orElseGet(() -> new Winner.Solo(last.uuid())));
        }

        Set<TeamId> aliveTeams = new HashSet<>();
        for (Participant p : alive) {
            if (p.teamId().isEmpty()) {
                // A teamless survivor next to other survivors: the round continues.
                return Optional.empty();
            }
            aliveTeams.add(p.teamId().get());
        }

        if (aliveTeams.size() == 1) {
            return Optional.of(teamWin(aliveTeams.iterator().next(), participants));
        }

        return Optional.empty();
    }

    /**
     * The result when time runs out. Unlike {@link #resolve}, this always returns a result: if no winner
     * has been decided, the round ends with {@link Winner.None}.
     */
    public Winner resolveOnTimeout(Collection<Participant> participants) {
        return resolve(participants).orElseGet(Winner.None::new);
    }

    private Winner.Team teamWin(TeamId teamId, Collection<Participant> participants) {
        Set<UUID> members = new HashSet<>();
        for (Participant p : participants) {
            if (p.teamId().filter(teamId::equals).isPresent()) {
                members.add(p.uuid());
            }
        }
        return new Winner.Team(teamId, members);
    }

    @Override
    public String describe() {
        return "whether the current roster has produced a winner";
    }
}
