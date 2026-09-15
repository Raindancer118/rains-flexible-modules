package de.raindancer.modules.manhunt.model;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One hunt in progress: who is running, who is chasing, and which Runners are already out.
 *
 * <h2>A snapshot, taken once, never re-read from the sides</h2>
 * The two teams are a lobby thing — people join a side, change their mind, leave. A hunt under way is
 * not: whoever pressed start raced with a particular pair of rosters, and every question afterwards
 * ("has the last Runner been caught", "does this Hunter get a compass") has to be answered against
 * that same pair. Asking the live teams instead would let somebody joining the Runners mid-hunt
 * resurrect a hunt the Hunters had already won.
 *
 * <h2>Everybody who is not a Runner is a Hunter</h2>
 * Deliberately, and it is the rule that makes the lobby usable: a hunt is normally one or two Runners
 * against everybody else, so picking a side is something the Runners do and nobody else has to. A
 * player who wandered into the lobby and pressed nothing is chasing, which is what they would have
 * chosen anyway, rather than a refused start nobody can explain.
 *
 * <h2>Bukkit-free</h2>
 * Ids and sets, so the rules of a hunt are tested without a server — the same split the tracking
 * compass has between {@code TrackerCompass} and its service.
 */
public final class Hunt {

    private final Set<UUID> runners;
    private final Set<UUID> hunters;
    /** Runners who have died. Written from a death event, read from the end condition's own check. */
    private final Set<UUID> eliminated = ConcurrentHashMap.newKeySet();

    private Hunt(Set<UUID> runners, Set<UUID> hunters) {
        this.runners = Set.copyOf(runners);
        this.hunters = Set.copyOf(hunters);
    }

    /**
     * @param participants everybody in the run, as the lobby swept them up
     * @param chosenRunners who is on the Runner side — only those actually in {@code participants}
     *                      count, since somebody who joined the Runners and then walked out of the
     *                      lobby world is not in this hunt at all
     */
    public static Hunt of(Set<UUID> participants, Set<UUID> chosenRunners) {
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(chosenRunners, "chosenRunners");
        Set<UUID> running = new LinkedHashSet<>(participants);
        running.retainAll(chosenRunners);
        Set<UUID> chasing = new LinkedHashSet<>(participants);
        chasing.removeAll(running);
        return new Hunt(running, chasing);
    }

    public Set<UUID> runners() {
        return runners;
    }

    public Set<UUID> hunters() {
        return hunters;
    }

    public boolean isRunner(UUID player) {
        return runners.contains(player);
    }

    public boolean isHunter(UUID player) {
        return hunters.contains(player);
    }

    /**
     * Takes {@code runner} out of the hunt.
     *
     * @return true the first time, so the caller can tell a real elimination from a second death
     *         event for somebody already out — a spectator cannot die, but a plugin can fire one
     */
    public boolean eliminate(UUID runner) {
        return isRunner(runner) && eliminated.add(runner);
    }

    public boolean isEliminated(UUID player) {
        return eliminated.contains(player);
    }

    /** Every Runner still in it. */
    public Set<UUID> livingRunners() {
        Set<UUID> alive = new LinkedHashSet<>(runners);
        alive.removeAll(eliminated);
        return Set.copyOf(alive);
    }

    /** Everybody the Runners have lost. */
    public Set<UUID> eliminated() {
        return Set.copyOf(eliminated);
    }

    /** Whether the Hunters have caught every Runner — the moment they win. */
    public boolean allRunnersOut() {
        return eliminated.containsAll(runners);
    }

    /** Everybody in the hunt, both sides. */
    public Set<UUID> everybody() {
        Set<UUID> both = new LinkedHashSet<>(runners);
        both.addAll(hunters);
        return Set.copyOf(both);
    }
}
