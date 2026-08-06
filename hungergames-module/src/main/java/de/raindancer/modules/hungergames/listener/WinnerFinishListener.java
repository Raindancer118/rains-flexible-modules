package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.store.GameEvents;

import java.time.Duration;
import java.util.UUID;

/**
 * The pause between a winner being crowned and the arena being taken down.
 *
 * <h2>Why a delay, and why it is not a detail</h2>
 * The moment somebody wins, two things want to happen: the victory — title, sound, the announcement with
 * their name on it — and the clear-up, which puts everybody back in the lobby, restores game modes and hands
 * the arena to whoever asked for the next round. Run them together and the clear-up wins the race: forty
 * people are teleported out of the arena at the instant the winner is named, and nobody sees the thing they
 * spent two hours playing for.
 *
 * <p>So the finish waits. Five seconds by default, which is long enough for a title to be read and a sound
 * to finish and short enough that nobody wonders whether the plugin has hung.
 *
 * <h2>Why this is a class rather than one scheduled call</h2>
 * Because it must happen <b>exactly once</b>, and there are two ways for a winner to be declared: the winner
 * rule finding one during an elimination, and a gamemaster ending the round on time. Both go through
 * {@link GameEvents#winnerDeclared}, and both used to schedule their own finish — a round that ended on the
 * clock at the same moment the last two killed each other ran the whole clear-up sequence twice, which on the
 * live server put people in the lobby and then teleported them back to a deleted arena.
 *
 * <p>The scheduling itself is Core's {@code Scheduling}, injected as {@link Later} — so this class is
 * testable by running the callback immediately, and works on Folia, where there is no main thread to post to.
 */
public final class WinnerFinishListener implements IHungerGamesListener, GameEvents {

    /**
     * How long the victory is left on screen before the arena is taken down.
     *
     * <p>Five seconds. The source used 100 ticks, which is the same number written in a unit that stops
     * being obvious the moment somebody changes the server's tick rate.
     */
    public static final Duration CURTAIN = Duration.ofSeconds(5);

    /** Running something after a delay — Core's {@code Scheduling.globalLater} in production. */
    @FunctionalInterface
    public interface Later {
        void run(Duration after, Runnable what);
    }

    /** Taking the arena down and putting everybody back. Supplied by whoever wires the module. */
    @FunctionalInterface
    public interface Finish {
        void now(Winner winner);
    }

    private final Later later;
    private final Finish finish;

    /**
     * Whether a finish has already been scheduled for this round.
     *
     * <p>The one piece of state, and the whole reason this is a class. See the class note: without it, a
     * round that ends on the clock at the same moment the last two tributes kill each other runs the
     * clear-up twice.
     *
     * <p>{@code volatile} because {@code winnerDeclared} can arrive from a region thread handling a death
     * and from the global scheduler handling the clock, and those are different threads on Folia.
     */
    private volatile boolean alreadyFinishing;

    public WinnerFinishListener(Later later, Finish finish) {
        this.later = later;
        this.finish = finish;
    }

    /** Runs the finish where it stands, for tests. Production passes {@code Scheduling.globalLater}. */
    public static Later immediately() {
        return (after, what) -> what.run();
    }

    /**
     * Swaps in the settings. Not an override — {@code GameEvents} has no such method and this is a
     * listener, not a service — but declared with the same name so the module wires every listener the
     * same way and nothing has to remember which of them cares.
     */
    public void settings(HungerGamesSettings settings) {
        // Nothing here is configurable, and the curtain deliberately is not: it exists so that the victory
        // is seen, and a server that set it to zero would be a server whose winners are never announced in
        // a way anybody notices. Declared so that a future setting cannot be added without this being seen.
    }

    @Override
    public void forget(UUID player) {
        // Nothing is kept per player. The one flag here is per round, and it is cleared by phaseChanged.
    }

    @Override
    public void winnerDeclared(Winner winner) {
        if (alreadyFinishing) {
            // The round already has a finish coming. Both doors — the winner rule and the clock — call
            // this, and on the live server both firing at once cleared the arena twice.
            return;
        }
        alreadyFinishing = true;
        later.run(CURTAIN, () -> finish.now(winner));
    }

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        // A fresh round may be finished again. Not resetting here is how the *second* tournament on a
        // server never cleans up at all — the flag was still set from the first.
        if (newPhase == GamePhase.NOT_INITIALIZED || newPhase == GamePhase.PREFLIGHT
                || newPhase == GamePhase.RUNNING) {
            alreadyFinishing = false;
        }
    }

    /** Whether a finish is pending. For the admin suite, and for the test that proves it happens once. */
    public boolean isFinishing() {
        return alreadyFinishing;
    }

    // ==================== the rest of GameEvents, which this has no opinion about ====================

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
    }

    @Override
    public void participantRevived(UUID participant) {
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
    }

    @Override
    public void teamCreated(Team team) {
    }

    @Override
    public void teamDeleted(Team team) {
    }

    @Override
    public void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour) {
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
    }

    @Override
    public String describe() {
        return "letting the victory be seen before the arena comes down";
    }
}
