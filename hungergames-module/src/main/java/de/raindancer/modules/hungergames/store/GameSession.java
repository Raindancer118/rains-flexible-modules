package de.raindancer.modules.hungergames.store;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.core.social.team.Teams;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.rules.WinnerRule;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The aggregate root of one Hunger Games round.
 *
 * <h2>Why every mutation goes through here</h2>
 * Every change to round state, tributes and teams runs through this class: it validates phase
 * transitions, announces every change through the {@link GameEvents} port, persists after every mutation
 * through {@link SessionStore}, and is where winner determination is triggered. Nothing downstream of this
 * class ever needs to ask "did that actually happen" — if a method here returned {@code true}, the event
 * fired and the snapshot was written before it returned.
 *
 * <p>Bukkit-free, and so fully testable without a server — which is the whole point of this wave of the
 * port: the game itself does not need a running Paper to be correct.
 */
public final class GameSession {

    private static final Map<GamePhase, Set<GamePhase>> ALLOWED_TRANSITIONS = new EnumMap<>(Map.of(
            GamePhase.NOT_INITIALIZED, EnumSet.of(GamePhase.PREFLIGHT),
            GamePhase.PREFLIGHT, EnumSet.of(GamePhase.LOBBY),
            GamePhase.LOBBY, EnumSet.of(GamePhase.STARTUP),
            // STARTUP -> LOBBY is the defined recovery path if the start-up sequence has to be aborted
            // (nobody arrived, an error occurred).
            GamePhase.STARTUP, EnumSet.of(GamePhase.READY, GamePhase.LOBBY),
            GamePhase.READY, EnumSet.of(GamePhase.RUNNING),
            GamePhase.RUNNING, EnumSet.of(GamePhase.FINISHED),
            GamePhase.FINISHED, EnumSet.of(GamePhase.NOT_INITIALIZED)));

    private final ParticipantRegistry participants;
    private final Teams teams;
    private final KillTracker kills = new KillTracker();
    private final WinnerRule winnerRule = new WinnerRule();
    private final GameEvents events;
    private final SessionStore store;
    private final GameClock clock;
    private final Random random;

    private GamePhase phase = GamePhase.NOT_INITIALIZED;
    private Winner winner;
    private Long runningSinceMillis;

    public GameSession(Supplier<TeamRules> teamRules, GameEvents events,
                        SessionStore store, GameClock clock, Random random) {
        this.events = events;
        this.store = store;
        this.clock = clock;
        this.random = random;
        // The roster is told *whether* teams are frozen, not what the phase is — it has no business
        // knowing a round has phases. The session owns both halves of that question, so it answers it.
        this.teams = new Teams(() -> teamRules.get().toPolicy(),
                () -> teamRules.get().isLocked(phase()), this::isWhitelisted);
        this.participants = new ParticipantRegistry(teams::teamIdOf);

        // Read back here, in the constructor, rather than left to whoever wires this up.
        //
        // It was left to them once, and nobody did it: the session wrote itself to disk on every mutation
        // and restore() sat unused, so a server restarted mid-round came back with no phase, no tributes,
        // no teams and no kills — with forty people still connected, standing in an arena the plugin no
        // longer believed existed. Nothing failed and nothing was logged. The wiring even asked
        // "is the phase RUNNING?" to decide whether to resume the clock, a condition that could not be
        // true because the load that would have made it true never happened.
        //
        // A session that reads its own store cannot be built in a state that has forgotten. That is worth
        // more than the flexibility of loading later, which nothing wanted.
        store.load().ifPresent(this::restoreQuietly);
    }

    /**
     * Restores without announcing anything.
     *
     * <p>{@link #restore} is what an admin screen or a test calls, and it is right that it does not fire
     * events either — but this one is called from the constructor, where {@code events} is a fan-out that has
     * not been subscribed to yet. Separate and named, so that the constructor's silence is deliberate rather
     * than an accident of ordering.
     */
    private void restoreQuietly(SessionSnapshot snapshot) {
        restore(snapshot);
    }

    // ==================== phases ====================

    public GamePhase phase() {
        return phase;
    }

    /**
     * Moves to the target phase, if the transition is allowed.
     *
     * @return {@code false} for a disallowed transition (state is left unchanged)
     */
    public boolean transitionTo(GamePhase target) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(phase, Set.of()).contains(target)) {
            return false;
        }
        GamePhase old = phase;
        phase = target;
        if (target == GamePhase.RUNNING) {
            runningSinceMillis = clock.nowMillis();
        }
        events.phaseChanged(old, target);
        persist();
        return true;
    }

    /** Resets the whole session (a fresh round, empty whitelist). */
    public void reset() {
        GamePhase old = phase;
        phase = GamePhase.NOT_INITIALIZED;
        winner = null;
        runningSinceMillis = null;
        participants.clear();
        teams.clear();
        kills.reset();
        store.clear();
        if (old != GamePhase.NOT_INITIALIZED) {
            events.phaseChanged(old, GamePhase.NOT_INITIALIZED);
        }
    }

    /**
     * Starts a new round with the same tributes and teams: eliminations, kills and the winner are reset.
     */
    public void resetForNextRound() {
        GamePhase old = phase;
        phase = GamePhase.NOT_INITIALIZED;
        winner = null;
        runningSinceMillis = null;
        participants.resetStates();
        kills.reset();
        persist();
        if (old != GamePhase.NOT_INITIALIZED) {
            events.phaseChanged(old, GamePhase.NOT_INITIALIZED);
        }
    }

    // ==================== whitelist / tributes ====================

    public boolean whitelistAdd(UUID uuid, String name) {
        if (!participants.add(uuid, name)) {
            return false;
        }
        events.whitelistChanged(uuid, true);
        persist();
        return true;
    }

    public boolean whitelistRemove(UUID uuid) {
        if (!participants.remove(uuid)) {
            return false;
        }
        Optional<TeamId> oldTeam = teams.forceRemove(uuid);
        oldTeam.ifPresent(old ->
                events.teamMembershipChanged(uuid, old, null, MembershipCause.API));
        events.whitelistChanged(uuid, false);
        persist();
        return true;
    }

    public boolean isWhitelisted(UUID uuid) {
        return participants.contains(uuid);
    }

    public ParticipantRegistry participants() {
        return participants;
    }

    /** Refreshes the display name (on join). */
    public void updateName(UUID uuid, String name) {
        participants.updateName(uuid, name);
        persist();
    }

    // ==================== elimination / victory ====================

    /**
     * Eliminates a tribute (death, or an admin action).
     *
     * <p>Counts the kill where there is one, fires events, and then checks for a winner. Only effective
     * during {@link GamePhase#RUNNING}.
     *
     * @param killer the killer, or {@code null}
     * @return {@code true} if the tribute was eliminated by this call
     */
    public boolean eliminate(UUID victim, UUID killer) {
        if (phase != GamePhase.RUNNING) {
            return false;
        }
        if (!participants.eliminate(victim)) {
            return false;
        }

        if (killer != null && !killer.equals(victim) && participants.isAlive(killer)) {
            int total = kills.increment(killer);
            events.kill(killer, victim, total);
        }

        events.participantEliminated(victim, killer, participants.aliveCount());
        persist();

        winnerRule.resolve(participants.all()).ifPresent(this::finish);
        return true;
    }

    /** Admin correction: undoes an elimination. */
    public boolean revive(UUID uuid) {
        if (!participants.revive(uuid)) {
            return false;
        }
        events.participantRevived(uuid);
        persist();
        return true;
    }

    /** Ends the round because time ran out (always produces a result). */
    public void declareTimeout() {
        if (phase == GamePhase.RUNNING) {
            finish(winnerRule.resolveOnTimeout(participants.all()));
        }
    }

    public Optional<Winner> winner() {
        return Optional.ofNullable(winner);
    }

    private void finish(Winner result) {
        winner = result;
        transitionTo(GamePhase.FINISHED);
        events.winnerDeclared(result);
        persist();
    }

    // ==================== teams (delegated, with events) ====================

    /**
     * The roster itself, for anything that only reads it.
     *
     * <p>Returned rather than wrapped in a further dozen delegating getters. What must go through the methods
     * below is every *change*, because each of them fires the event and writes the snapshot — a caller that
     * mutated the roster directly would leave the scoreboard, the tablist and {@code session.yml} describing a
     * round that no longer exists.
     */
    public Teams teams() {
        return teams;
    }

    public Teams.CreationResult teamCreate(String name, TeamColour colour) {
        Teams.CreationResult result = teams.create(name, colour);
        result.team().ifPresent(team -> {
            events.teamCreated(team);
            persist();
        });
        return result;
    }

    public TeamOutcome teamDelete(TeamId id) {
        // Asked before delegating, because delete answers with an empty Optional for both "no such team" and
        // "teams are frozen", and those two need different sentences. A gamemaster told there is no team called
        // red, about a team they are looking at, goes hunting for a bug in the roster; told teams are locked,
        // they know the round has to end first. Checked in this order so that a stale menu naming a team that
        // really is gone still says so.
        if (teams.team(id).isPresent() && teams.isFrozen()) {
            return TeamOutcome.FROZEN;
        }
        Optional<Team> deleted = teams.delete(id);
        if (deleted.isEmpty()) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        events.teamDeleted(deleted.get());
        persist();
        return TeamOutcome.SUCCESS;
    }

    public TeamOutcome teamRename(TeamId id, String newName) {
        TeamOutcome result = teams.rename(id, newName);
        if (result.isSuccess()) {
            persist();
        }
        return result;
    }

    public TeamOutcome teamSetColour(TeamId id, TeamColour colour) {
        // The old colour is read before the change, because after it there is nothing left to compare against
        // and the event carries both. Nothing fires when the colour was already that one: Teams answers
        // SUCCESS for a no-op, which is right for the caller and would otherwise be broadcast as a change.
        Optional<TeamColour> before = teams.team(id).map(Team::colour);
        TeamOutcome result = teams.setColour(id, colour);
        if (result.isSuccess() && before.isPresent() && before.get() != colour) {
            events.teamColourChanged(teams.team(id).orElseThrow(), before.get(), colour);
            persist();
        }
        return result;
    }

    public TeamOutcome teamAssign(UUID player, TeamId target, MembershipCause cause) {
        Teams.MembershipChange change = teams.join(player, target);
        if (change.status().isSuccess()) {
            events.teamMembershipChanged(player, change.oldTeam().orElse(null), target, cause);
            persist();
        }
        return change.status();
    }

    public TeamOutcome teamRemovePlayer(UUID player, MembershipCause cause) {
        Teams.MembershipChange change = teams.leave(player);
        if (change.status().isSuccess()) {
            events.teamMembershipChanged(player, change.oldTeam().orElse(null), null, cause);
            persist();
        }
        return change.status();
    }

    public TeamOutcome teamSetCaptain(TeamId id, UUID player) {
        TeamOutcome result = teams.setCaptain(id, player);
        if (result.isSuccess()) {
            persist();
        }
        return result;
    }

    /** Randomly assigns every teamless tribute. @return the number of players assigned */
    public int teamAssignRandomly() {
        Map<UUID, TeamId> assigned = teams.assignRandomly(participants.alive(), random);
        assigned.forEach((player, team) ->
                events.teamMembershipChanged(player, null, team, MembershipCause.RANDOM));
        if (!assigned.isEmpty()) {
            persist();
        }
        return assigned.size();
    }

    // ==================== stats ====================

    public KillTracker kills() {
        return kills;
    }

    /** The epoch instant {@link GamePhase#RUNNING} began, if the round has started. */
    public Optional<Long> runningSinceMillis() {
        return Optional.ofNullable(runningSinceMillis);
    }

    // ==================== persistence ====================

    /** Restores the session from a saved snapshot. */
    public void restore(SessionSnapshot snapshot) {
        phase = snapshot.phase();
        winner = snapshot.winner();
        runningSinceMillis = snapshot.runningSinceMillis();
        teams.restore(snapshot.teams());
        participants.restore(snapshot.participants());
        kills.restore(snapshot.kills());
    }

    public SessionSnapshot snapshot() {
        return new SessionSnapshot(
                phase,
                participants.all().stream()
                        .map(p -> new SessionSnapshot.ParticipantData(p.uuid(), p.lastKnownName(), p.state()))
                        .toList(),
                // Core's own snapshot, not a shape of this module's making. The teams *are* Core's now, so a
                // TeamData record here would be a second description of one thing — and the two would drift
                // the first time Core's Team grew a field, with the restore quietly dropping it.
                teams.snapshot(),
                winner,
                kills.snapshot(),
                runningSinceMillis);
    }

    private void persist() {
        store.save(snapshot());
    }
}
