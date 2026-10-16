package de.raindancer.modules.ctf.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One round: the map as built, the sides, their flags and scores, everybody in it. All of it is changed only while
 * holding the round itself ({@code synchronized (round)}); readers outside take {@link #fighters()} and the like as
 * copies.
 *
 * <p>Map spots are relative; {@link #abs} turns one into the real place, the map having been built with its lowest
 * corner at {@code origin}.
 */
public final class Round {

    private final UUID id = UUID.randomUUID();
    private final CtfMap map;
    private final GameType type;
    private final Mutator mutator;
    private final String world;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final List<Side> sides;
    private final int[] scores;
    private final List<Flag> flags = new ArrayList<>();
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private Phase phase = Phase.PREPARING;
    private long startedAt;
    private long endsAt;
    private int half = 1;
    private int defenders;
    private int controlOwner = -1;
    private int controlLeaning = -1;
    private int controlProgress;
    private long nextSwap;
    private boolean suddenDeath;
    private final java.util.Set<Integer> flagLeftHome = new java.util.HashSet<>();

    public Round(CtfMap map, GameType type, Mutator mutator, String world, int originX, int originY, int originZ,
                 List<Side> sides) {
        this.map = map;
        this.type = type;
        this.mutator = mutator;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sides = new ArrayList<>(sides);
        this.scores = new int[sides.size()];
    }

    public UUID id() {
        return id;
    }

    public CtfMap map() {
        return map;
    }

    public GameType type() {
        return type;
    }

    public Mutator mutator() {
        return mutator;
    }

    public String world() {
        return world;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public Point abs(Point relative) {
        return relative == null ? null : relative.plus(originX, originY, originZ);
    }

    /** The real height below which a player has fallen off. */
    public int floorY() {
        return originY + map.floor();
    }

    public List<Side> sides() {
        return List.copyOf(sides);
    }

    public Side side(int index) {
        return sides.get(index);
    }

    public void side(int index, Side changed) {
        sides.set(index, changed);
    }

    public Base baseOf(int side) {
        return map.completeBases().get(sides.get(side).base());
    }

    public int score(int side) {
        return scores[side];
    }

    public int[] scores() {
        return scores.clone();
    }

    public void scored(int side) {
        scores[side]++;
    }

    public List<Flag> flags() {
        return List.copyOf(flags);
    }

    public void addFlag(Flag flag) {
        flags.add(flag);
    }

    /** All flags at once — a one-flag round's half time hands the flag to the other side. */
    public void replaceFlags(List<Flag> next) {
        flags.clear();
        flags.addAll(next);
    }

    /** The flag of a side — or the neutral one for {@link Flag#NEUTRAL}. */
    public Optional<Flag> flagOf(int side) {
        return flags.stream().filter(flag -> flag.side() == side).findFirst();
    }

    public Optional<Flag> carriedBy(UUID player) {
        return flags.stream().filter(flag -> flag.isCarriedBy(player)).findFirst();
    }

    public Optional<Fighter> fighter(UUID player) {
        return Optional.ofNullable(fighters.get(player));
    }

    public Collection<Fighter> fighters() {
        return List.copyOf(fighters.values());
    }

    public List<Fighter> onSide(int side) {
        return fighters.values().stream().filter(fighter -> fighter.side() == side).toList();
    }

    public void add(Fighter fighter) {
        fighters.put(fighter.id(), fighter);
    }

    public Optional<Fighter> remove(UUID player) {
        return Optional.ofNullable(fighters.remove(player));
    }

    public boolean has(UUID player) {
        return fighters.containsKey(player);
    }

    public Phase phase() {
        return phase;
    }

    public void phase(Phase next) {
        phase = next;
    }

    public long startedAt() {
        return startedAt;
    }

    public long endsAt() {
        return endsAt;
    }

    public void clock(long started, long ends) {
        startedAt = started;
        endsAt = ends;
    }

    public void endsAt(long ends) {
        endsAt = ends;
    }

    public int half() {
        return half;
    }

    public void secondHalf() {
        half = 2;
    }

    /** In one-flag rounds, the side that defends this half. */
    public int defenders() {
        return defenders;
    }

    public void defenders(int side) {
        defenders = side;
    }

    public int controlOwner() {
        return controlOwner;
    }

    public int controlLeaning() {
        return controlLeaning;
    }

    public int controlProgress() {
        return controlProgress;
    }

    public void control(int owner, int leaning, int progress) {
        controlOwner = owner;
        controlLeaning = leaning;
        controlProgress = progress;
    }

    public long nextSwap() {
        return nextSwap;
    }

    public void nextSwap(long at) {
        nextSwap = at;
    }

    /** Sudden death: the flags stand in the middle and a side scores at its own base. */
    public boolean isSuddenDeath() {
        return suddenDeath;
    }

    public void suddenDeath() {
        suddenDeath = true;
    }

    /** Remembers that a side's flag was taken at least once — for a clean sheet. */
    public void flagLeftHome(int side) {
        flagLeftHome.add(side);
    }

    public boolean flagEverLeftHome(int side) {
        return flagLeftHome.contains(side);
    }

    /** The side with the most points, if it is the only one with that many. */
    public Optional<Integer> leader() {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (int side = 0; side < scores.length; side++) {
            if (scores[side] > bestScore) {
                best = side;
                bestScore = scores[side];
                tie = false;
            } else if (scores[side] == bestScore) {
                tie = true;
            }
        }
        return tie || best < 0 ? Optional.empty() : Optional.of(best);
    }
}
