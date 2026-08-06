package de.raindancer.modules.hungergames.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Every listener that wants to hear what the round just did, behind the one port the session speaks through.
 *
 * <h2>Why the session takes one and not a list</h2>
 * {@link GameSession} calls {@link GameEvents} at the exact moment it changes, inside the method that
 * changed it. Handing it a list would mean the session iterating, catching, and deciding what to do when the
 * third listener throws halfway through a phase transition — which is a fan-out concern that has nothing to
 * do with running a tournament. It takes one port; this is what makes that one port several.
 *
 * <h2>Why one listener throwing does not stop the others</h2>
 * The subscribers here are the round log, the announcement feed, the op tracker and the timer. They are
 * genuinely independent: an announcement that fails to render must not stop the op tracker restoring
 * somebody's operator status, and a round log whose file has gone read-only must not stop the round being
 * announced as won. So each call is isolated and a failure is logged with the subscriber named, rather than
 * unwinding back into the session and leaving the phase half-changed.
 *
 * <p>That is not a licence for subscribers to throw. It is the recognition that this call happens inside
 * {@code transitionTo}, and an exception escaping it would leave the phase already assigned, the disk
 * already written, and the caller believing nothing happened.
 */
public final class AllGameEvents implements GameEvents {

    private static final LogChannel log = Log.of("hungergames");

    private final List<GameEvents> subscribers = new CopyOnWriteArrayList<>();

    public AllGameEvents(GameEvents... initial) {
        subscribers.addAll(List.of(initial));
    }

    /**
     * Adds a subscriber after the session already exists.
     *
     * <p>Not a convenience — it is the only way to wire this at all. Half the things that want to hear about
     * a round need the session to be constructed: the op tracker asks it who is a tribute, the timer asks it
     * how long the round has been going, the announcer asks it who is still alive. But the session needs a
     * {@link GameEvents} in its own constructor, so the two cannot both be built first.
     *
     * <p>The alternative is passing every subscriber a supplier of a session that does not exist yet, which
     * moves the same cycle into ten constructors instead of resolving it in one. This resolves it: the
     * session is built with an empty fan-out, and everything that needs the session subscribes once it has
     * one.
     *
     * <p>{@link CopyOnWriteArrayList} rather than a plain list because subscribing happens during start-up
     * while a phase change can already be firing — a session restored from disk announces its phase as it
     * loads.
     */
    public AllGameEvents also(GameEvents subscriber) {
        subscribers.add(subscriber);
        return this;
    }

    /** How many are listening — for a diagnostic screen, and for the test that checks nothing was dropped. */
    public int size() {
        return subscribers.size();
    }

    private void each(String what, Consumer<GameEvents> call) {
        for (GameEvents subscriber : subscribers) {
            try {
                call.accept(subscriber);
            } catch (RuntimeException failed) {
                log.error("{} threw while handling {}: {}",
                        subscriber.getClass().getSimpleName(), what, failed.toString());
            }
        }
    }

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        each("a phase change", one -> one.phaseChanged(oldPhase, newPhase));
    }

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        each("an elimination", one -> one.participantEliminated(participant, killer, remainingAlive));
    }

    @Override
    public void participantRevived(UUID participant) {
        each("a revival", one -> one.participantRevived(participant));
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
        each("a whitelist change", one -> one.whitelistChanged(player, added));
    }

    @Override
    public void teamCreated(Team team) {
        each("a team being created", one -> one.teamCreated(team));
    }

    @Override
    public void teamDeleted(Team team) {
        each("a team being deleted", one -> one.teamDeleted(team));
    }

    @Override
    public void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour) {
        each("a team colour change", one -> one.teamColourChanged(team, oldColour, newColour));
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
        each("a team membership change",
                one -> one.teamMembershipChanged(player, oldTeam, newTeam, cause));
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
        each("a kill", one -> one.kill(killer, victim, killerTotalKills));
    }

    @Override
    public void winnerDeclared(Winner winner) {
        each("a winner", one -> one.winnerDeclared(winner));
    }
}
