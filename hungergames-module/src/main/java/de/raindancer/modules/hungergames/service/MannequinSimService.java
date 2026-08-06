package de.raindancer.modules.hungergames.service;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A test simulation, run with mannequins that are real {@link GameSession} tributes on real, auto-created
 * test teams — so eliminating one runs exactly the same elimination, winner and deathmatch flow a real
 * death would, and a single admin can rehearse a whole round alone.
 *
 * <h2>Why a mannequin's team is never checked against a size limit here</h2>
 * The source engine read its own {@code team-max-size} setting to decide when to open a new test team.
 * That key has no home in {@code HungerGamesSettings} yet (see {@code SponsorTokenService}'s class note on
 * the general shape of that gap), and duplicating the limit here would be a second, private copy of
 * whatever {@link de.raindancer.core.social.team.Teams} is already configured to enforce — exactly what
 * {@code ReuseTest} exists to catch. So {@link #spawn} simply tries the roster's own answer: assign to the
 * team currently being filled, and open a new one only when {@code Teams} itself refuses with
 * {@link TeamOutcome#TEAM_FULL}. Whatever size Core enforces is the size a simulation respects,
 * automatically, forever.
 *
 * <h2>Standing them on the platforms</h2>
 * {@link #placeOnPlatforms} is what makes a rehearsal look like the round it is rehearsing: mannequins are
 * moved onto the real starting platforms when the start-up sequence reaches {@code READY} with nobody real
 * online. It takes the positions rather than working them out, because the arena is
 * {@code ArenaBuildService}'s to know about — this class only knows which mannequin is whose.
 */
public final class MannequinSimService implements IHungerGamesService, AdminEndpoints.Simulation {

    private static final String TEST_TEAM_PREFIX = "Test-Team ";

    /** The one seam that touches the world: putting a mannequin down, and taking it away again. */
    public interface Mannequins {

        /** Spawns a mannequin near {@code base}, dressed in {@code colour}, and returns its entity id. */
        UUID spawn(Location base, String displayName, TeamColour colour);

        /** Removes the mannequin entity, if it still exists. */
        void remove(UUID entityId);

        /** The mannequin's current location, for a death effect — empty if it no longer exists. */
        Optional<Location> locationOf(UUID entityId);

        /** Puts a mannequin somewhere else — used to stand them on the arena's platforms for a rehearsal. */
        void moveTo(UUID entityId, Location where);

        /** Plays whatever marks a mannequin's elimination (a lightning strike, a cue) at {@code location}. */
        void markEliminated(Location location, boolean hadKiller);
    }

    @FunctionalInterface
    public interface RoundLog {
        void log(String category, String message, Location location);

        default void log(String category, String message) {
            log(category, message, null);
        }
    }

    private final GameSession session;
    private final Mannequins mannequins;
    private final RoundLog roundLog;

    /** Participant UUID to the entity UUID of its mannequin. */
    private final Map<UUID, UUID> entityByParticipant = new LinkedHashMap<>();
    private final List<TeamId> simTeams = new ArrayList<>();

    private int tributeCounter;
    private int teamCounter;
    private TeamId fillTeam;

    public MannequinSimService(GameSession session, Mannequins mannequins, RoundLog roundLog) {
        this.session = session;
        this.mannequins = mannequins;
        this.roundLog = roundLog;
    }

    /** Nothing here reads a setting today — see {@link IHungerGamesService}'s note on implementing it empty. */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty
    }

    // ==================== AdminEndpoints.Simulation — status ====================

    @Override
    public int mannequinCount() {
        return entityByParticipant.size();
    }

    @Override
    public int aliveCount() {
        int alive = 0;
        for (UUID uuid : entityByParticipant.keySet()) {
            if (session.participants().isAlive(uuid)) {
                alive++;
            }
        }
        return alive;
    }

    @Override
    public int teamCount() {
        return simTeams.size();
    }

    /** Spawning is only possible before teams freeze — the same rule real registration is judged against. */
    @Override
    public boolean canSpawn() {
        return !session.teams().isFrozen();
    }

    /**
     * Moves every mannequin onto a starting platform, one each, in the order they were spawned.
     *
     * <p>This is what the class note used to call out as deliberately not ported: there was no arena builder
     * to read platform positions from, so a mannequin stayed wherever the admin who spawned it happened to
     * be standing — which made a rehearsal look nothing like the round it was rehearsing. There is one now,
     * and the start-up sequence calls this when it reaches {@code READY} on a run with nobody real online.
     *
     * <p>Mannequins beyond the number of platforms are left where they are rather than stacked on the last
     * one: a simulation with more tributes than the arena was built for is a mistake worth being able to see
     * from the middle of the arena.
     *
     * @return how many were actually moved
     */
    public int placeOnPlatforms(List<Location> platforms) {
        int moved = 0;
        for (UUID participant : entityByParticipant.keySet()) {
            if (moved >= platforms.size()) {
                break;
            }
            if (!session.participants().isAlive(participant)) {
                continue;   // an eliminated mannequin is not put back on a platform
            }
            mannequins.moveTo(entityByParticipant.get(participant), platforms.get(moved));
            moved++;
        }
        if (moved > 0) {
            roundLog.log("SIMULATION", moved + " mannequin(s) placed on starting platforms");
        }
        return moved;
    }

    // ==================== spawn ====================

    @Override
    public String spawn(Player admin, int count) {
        if (!canSpawn()) {
            return "mannequins can only be spawned before the start-up sequence (phase "
                    + session.phase() + ")";
        }
        if (count <= 0) {
            return "count must be positive";
        }
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            String name = "Tribute-" + String.format("%02d", tributeCounter + 1);
            if (!session.whitelistAdd(uuid, name)) {
                continue;
            }
            tributeCounter++;
            Optional<TeamId> team = assignToAFillTeam(uuid);
            if (team.isEmpty()) {
                session.whitelistRemove(uuid);
                return spawned + "/" + count + " mannequin(s) spawned — no free team colours/slots left";
            }
            TeamColour colour = session.teams().team(team.get()).map(Team::colour).orElse(TeamColour.WHITE);
            entityByParticipant.put(uuid, mannequins.spawn(admin.getLocation(), name, colour));
            spawned++;
        }
        roundLog.log("SIM", admin.getName() + " spawned " + spawned + " mannequin(s)", admin.getLocation());
        return spawned + " mannequin tribute(s) spawned (" + simTeams.size() + " test team(s)).";
    }

    /**
     * Assigns {@code uuid} to the team currently being filled, opening a fresh {@code Test-Team N} the
     * moment that one refuses with {@link TeamOutcome#TEAM_FULL} — see the class note on why capacity
     * itself is never read or copied here.
     *
     * @return the team {@code uuid} ended up on, or empty when even a fresh team could not be created (no
     *         colour left, or the round's own team cap already reached)
     */
    private Optional<TeamId> assignToAFillTeam(UUID uuid) {
        if (fillTeam == null && openNewFillTeam().isEmpty()) {
            return Optional.empty();
        }
        TeamOutcome outcome = session.teamAssign(uuid, fillTeam, MembershipCause.API);
        if (outcome == TeamOutcome.TEAM_FULL) {
            if (openNewFillTeam().isEmpty()) {
                return Optional.empty();
            }
            outcome = session.teamAssign(uuid, fillTeam, MembershipCause.API);
        }
        return outcome.isSuccess() ? Optional.of(fillTeam) : Optional.empty();
    }

    private Optional<TeamId> openNewFillTeam() {
        String name = TEST_TEAM_PREFIX + (teamCounter + 1);
        var result = session.teamCreate(name, null);
        if (result.team().isEmpty()) {
            return Optional.empty();
        }
        teamCounter++;
        fillTeam = result.team().get().id();
        simTeams.add(fillTeam);
        return Optional.of(fillTeam);
    }

    // ==================== eliminate ====================

    @Override
    public Optional<String> eliminateOne(Player actor) {
        if (session.phase() != GamePhase.RUNNING) {
            return Optional.of("eliminating only works while RUNNING (phase " + session.phase() + ")");
        }
        for (UUID uuid : List.copyOf(entityByParticipant.keySet())) {
            if (session.participants().isAlive(uuid)) {
                eliminate(uuid, actor.getUniqueId());
                return Optional.empty();
            }
        }
        return Optional.of("no living mannequin left");
    }

    private void eliminate(UUID participant, UUID killer) {
        UUID entityId = entityByParticipant.get(participant);
        Optional<Location> deathLocation = entityId == null ? Optional.empty() : mannequins.locationOf(entityId);
        boolean eliminated = session.eliminate(participant, killer);
        if (!eliminated) {
            return;
        }
        deathLocation.ifPresent(location -> mannequins.markEliminated(location, killer != null));
        if (entityId != null) {
            mannequins.remove(entityId);
        }
    }

    // ==================== clear ====================

    @Override
    public int clear() {
        int removed = entityByParticipant.size();
        for (Map.Entry<UUID, UUID> entry : entityByParticipant.entrySet()) {
            if (entry.getValue() != null) {
                mannequins.remove(entry.getValue());
            }
            session.whitelistRemove(entry.getKey());
        }
        for (TeamId team : List.copyOf(simTeams)) {
            session.teamDelete(team);
        }
        entityByParticipant.clear();
        simTeams.clear();
        fillTeam = null;
        tributeCounter = 0;
        teamCounter = 0;
        return removed;
    }

    @Override
    public String describe() {
        return "a mannequin test simulation, run through the real elimination and winner flow";
    }
}
