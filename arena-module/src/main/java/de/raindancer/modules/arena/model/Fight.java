package de.raindancer.modules.arena.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One fight from start to end: who is on which side, who is still standing this round, how many rounds each side has
 * won — and for mob waves, which wave it is and which mobs are still alive.
 *
 * <p>No server in here: every step is a method that says what it led to, so the whole course of a fight — a best-of-three
 * decided in the third round, a forfeit in the middle of one, a free-for-all down to the last — is tested as plain values.
 * Not thread-safe on its own; the service that runs a fight does so on one thread.
 */
public final class Fight {

    public enum State { STARTING, FIGHTING, BETWEEN_ROUNDS, OVER }

    /** How a round or the fight ended. {@code side} is -1 for a draw, or for mobs winning a wave run. */
    public record Outcome(int side, boolean fightOver, String why) {
    }

    private final UUID id;
    private final MatchSpec spec;
    private final Pit pit;
    private final List<Set<UUID>> sides;
    private final Set<UUID> standing = new LinkedHashSet<>();
    private final Set<UUID> gone = new HashSet<>();
    private final int[] roundsWon;
    private final Set<UUID> mobs = new HashSet<>();
    private final java.util.Map<UUID, Integer> kills = new java.util.HashMap<>();
    private State state = State.STARTING;
    private int round = 1;
    private int wave = 0;
    private Outcome last;

    public Fight(UUID id, MatchSpec spec, Pit pit, List<Set<UUID>> sides) {
        if (sides.isEmpty() || sides.stream().anyMatch(Set::isEmpty)) {
            throw new IllegalArgumentException("every side needs somebody on it");
        }
        this.id = id;
        this.spec = spec;
        this.pit = pit;
        List<Set<UUID>> copy = new ArrayList<>();
        sides.forEach(side -> copy.add(Set.copyOf(side)));
        this.sides = List.copyOf(copy);
        this.roundsWon = new int[sides.size()];
        resetRound();
    }

    public UUID id() {
        return id;
    }

    public MatchSpec spec() {
        return spec;
    }

    public Pit pit() {
        return pit;
    }

    public State state() {
        return state;
    }

    public int round() {
        return round;
    }

    public int wave() {
        return wave;
    }

    public List<Set<UUID>> sides() {
        return sides;
    }

    public Optional<Outcome> last() {
        return Optional.ofNullable(last);
    }

    public int roundsWon(int side) {
        return roundsWon[side];
    }

    public int kills(UUID player) {
        return kills.getOrDefault(player, 0);
    }

    /** Everybody in it who has not left, standing or not. */
    public Set<UUID> players() {
        Set<UUID> all = new LinkedHashSet<>();
        sides.forEach(all::addAll);
        all.removeAll(gone);
        return all;
    }

    public boolean has(UUID player) {
        return players().contains(player);
    }

    public boolean isStanding(UUID player) {
        return standing.contains(player);
    }

    public Set<UUID> standing() {
        return Set.copyOf(standing);
    }

    public int sideOf(UUID player) {
        for (int side = 0; side < sides.size(); side++) {
            if (sides.get(side).contains(player)) {
                return side;
            }
        }
        return -1;
    }

    /** Whether these two may hurt each other right now: standing, fighting, on different sides — or one is its mob. */
    public boolean opponents(UUID a, UUID b) {
        if (state != State.FIGHTING) {
            return false;
        }
        boolean aMob = mobs.contains(a);
        boolean bMob = mobs.contains(b);
        if (aMob || bMob) {
            return aMob != bMob && (aMob ? standing.contains(b) : standing.contains(a));
        }
        return standing.contains(a) && standing.contains(b) && sideOf(a) != sideOf(b);
    }

    public void begin() {
        state = State.FIGHTING;
    }

    /**
     * Somebody is beaten.
     *
     * @param by who beat them, if anybody — counted as a kill
     * @return the outcome, when this ended the round (or the fight)
     */
    public Optional<Outcome> beaten(UUID player, UUID by) {
        if (state != State.FIGHTING || !standing.remove(player)) {
            return Optional.empty();
        }
        if (by != null && !by.equals(player) && has(by)) {
            kills.merge(by, 1, Integer::sum);
        }
        return settle();
    }

    /** Somebody left — quit, went back to survival. Out of this round and every later one. */
    public Optional<Outcome> left(UUID player) {
        if (!has(player)) {
            return Optional.empty();
        }
        gone.add(player);
        standing.remove(player);
        if (state == State.OVER) {
            return Optional.empty();
        }
        if (state != State.FIGHTING) {
            // Between rounds or before the start: a side nobody is left on has lost the whole thing.
            List<Integer> left = sidesWithPlayers();
            if (spec.mode() == Mode.WAVES && left.isEmpty()) {
                return Optional.of(end(-1, "everybody left"));
            }
            if (spec.mode() != Mode.WAVES && left.size() == 1) {
                return Optional.of(end(left.getFirst(), "the other side left"));
            }
            if (left.isEmpty()) {
                return Optional.of(end(-1, "everybody left"));
            }
            return Optional.empty();
        }
        return settle();
    }

    /** The time is up with nobody beaten: a draw, and the whole fight ends — nobody wins a stake by waiting. */
    public Outcome timeUp() {
        return end(-1, "time");
    }

    // ── mob waves ──────────────────────────────────────────────────────────────────────────────────

    /** The next wave begins with these mobs. */
    public void waveStarted(Set<UUID> spawned) {
        wave++;
        mobs.clear();
        mobs.addAll(spawned);
    }

    public boolean isMob(UUID entity) {
        return mobs.contains(entity);
    }

    public Set<UUID> mobs() {
        return Set.copyOf(mobs);
    }

    /** @return true when this was the last mob of the wave */
    public boolean mobGone(UUID entity, UUID killer) {
        if (!mobs.remove(entity)) {
            return false;
        }
        if (killer != null && standing.contains(killer)) {
            kills.merge(killer, 1, Integer::sum);
        }
        return mobs.isEmpty() && state == State.FIGHTING;
    }

    /** Whether the wave just cleared was the last one of the run. */
    public boolean lastWaveCleared() {
        return spec.waves() != null && !spec.waves().isEndless() && wave >= spec.waves().waves();
    }

    /** The players cleared every wave. */
    public Outcome wavesCleared() {
        return end(0, "every wave cleared");
    }

    // ── rounds ─────────────────────────────────────────────────────────────────────────────────────

    /** After a round, the next one: everybody still in the fight is standing again. */
    public void nextRound() {
        if (state != State.BETWEEN_ROUNDS) {
            throw new IllegalStateException("no round to go on to");
        }
        round++;
        resetRound();
        state = State.STARTING;
    }

    private void resetRound() {
        standing.clear();
        standing.addAll(players());
    }

    private Optional<Outcome> settle() {
        if (spec.mode() == Mode.WAVES) {
            return standing.isEmpty() ? Optional.of(end(-1, "the mobs won")) : Optional.empty();
        }
        List<Integer> alive = new ArrayList<>();
        for (int side = 0; side < sides.size(); side++) {
            for (UUID player : sides.get(side)) {
                if (standing.contains(player)) {
                    alive.add(side);
                    break;
                }
            }
        }
        if (alive.size() > 1) {
            return Optional.empty();
        }
        if (alive.isEmpty()) {
            return Optional.of(end(-1, "nobody left standing"));
        }
        int winner = alive.getFirst();
        roundsWon[winner]++;
        boolean decided = roundsWon[winner] > spec.bestOf() / 2 || sidesWithPlayers().size() < 2;
        if (decided) {
            return Optional.of(end(winner, spec.bestOf() > 1 ? "won " + roundsWon[winner] + " rounds" : "last standing"));
        }
        state = State.BETWEEN_ROUNDS;
        last = new Outcome(winner, false, "round " + round);
        return Optional.of(last);
    }

    private List<Integer> sidesWithPlayers() {
        List<Integer> left = new ArrayList<>();
        for (int side = 0; side < sides.size(); side++) {
            for (UUID player : sides.get(side)) {
                if (!gone.contains(player)) {
                    left.add(side);
                    break;
                }
            }
        }
        return left;
    }

    private Outcome end(int side, String why) {
        state = State.OVER;
        last = new Outcome(side, true, why);
        return last;
    }
}
