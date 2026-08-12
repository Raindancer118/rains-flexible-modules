package de.raindancer.modules.moderation.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The actual shape of what a player has been digging, not just how much of it was ore.
 *
 * <h2>Why this exists alongside {@link MiningWindow}</h2>
 * {@link MiningWindow} answers "is this worth a report" as fast as possible, on every single block
 * broken, and answers it with nothing but a count — that is exactly what makes it cheap enough to run
 * on the hot path. This answers a slower, different question, asked once a report is already open:
 * not "how much ore", but "how did they get to it" — the difference between a diamond somebody
 * stumbled into at the end of an ordinary tunnel and a diamond somebody walked straight at across
 * solid stone that had no business telling them it was there.
 *
 * <h2>The one heuristic this offers, and what it does not claim</h2>
 * For each ore block remembered, this walks backward through whatever was mined immediately before
 * it — for as long as each step is close enough to the last to plausibly be the same dig, see
 * {@link #MAX_STEP_DISTANCE} — and compares the straight-line distance covered against how many
 * blocks that took. A meandering tunnel covers little net distance for a lot of digging; somebody who
 * walked directly at ore they could not see covers nearly all of it. That is a fact about the
 * <em>shape</em> of a path and nothing more: a short natural cave, a lucky vein followed along its own
 * straight seam, or simply too little remembered context yet can all read the same way without anyone
 * having cheated. It is offered as one more thing worth looking at before a ban, never as an answer by
 * itself — see {@code XrayReviewMenu}, the one place this is ever shown to anybody.
 *
 * <h2>Why the whole path is kept, not only the ore</h2>
 * The context <em>is</em> the evidence. A player who mined nothing but ore in a straight line for
 * fifteen blocks, and a player who broke one exposed diamond while carving out a room, both "have a
 * diamond in their trail" — only the blocks around it tell those two apart.
 */
public final class MiningTrail {

    /**
     * How far apart two blocks mined one after another may be and still count as the same dig.
     *
     * <p>Generous rather than exact: an ordinary pickaxe reaches a little past one block, and diagonal
     * digging covers more than one axis at once. Anything past this is not a gap in a tunnel — it is
     * somebody who walked away and started mining somewhere else entirely, and counting that stretch
     * as "the approach" would blame a diamond for a walk that had nothing to do with it.
     */
    private static final double MAX_STEP_DISTANCE = 4.0;

    /**
     * How far back to ever look, even along one unbroken dig.
     *
     * <p>A tunnel that has gone perfectly straight for a hundred blocks is already about as
     * suspicious after this many as it will ever look, and bounding it keeps one long-lived player's
     * trail from costing more to read than everybody else's.
     */
    private static final int MAX_LOOKBACK = 40;

    private final int capacity;
    private final Deque<MinedBlock> path = new ArrayDeque<>();

    public MiningTrail(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Remembers one more block mined, dropping the oldest once the trail is full. */
    public synchronized void record(MinedBlock block) {
        path.addLast(block);
        if (path.size() > capacity) {
            path.removeFirst();
        }
    }

    /**
     * Every ore currently remembered whose material is on the watched list, each with how direct the
     * digging leading up to it looks.
     *
     * <p>Read against the <em>current</em> list rather than one frozen at the moment each block was
     * recorded — an owner who adds a material to the watched list after the fact should see it in
     * whatever trail is already sitting there, not only in what gets mined from that point on.
     *
     * @param oreNames material names, matched case-insensitively — the same list a server configures
     *                 for x-ray detection
     */
    public synchronized List<ApproachReading> oreApproaches(Collection<String> oreNames) {
        List<MinedBlock> chronological = new ArrayList<>(path);
        List<ApproachReading> found = new ArrayList<>();
        for (int index = 0; index < chronological.size(); index++) {
            if (isWatched(chronological.get(index).material(), oreNames)) {
                approachTo(chronological, index).ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * The same reading {@link #oreApproaches} would give for whatever was just {@link #record}ed —
     * or nothing, if it is not a watched material or there is no "before" to compare it to.
     *
     * <p>For a caller updating something incrementally every time one more ore block comes in, which
     * is the only reason this exists rather than everybody simply calling {@link #oreApproaches} and
     * looking at the last entry: that call rebuilds every reading in the whole trail on every single
     * ore block, and a caller only interested in the newest one should not pay for the rest.
     */
    public synchronized Optional<ApproachReading> approachToMostRecent(Collection<String> oreNames) {
        MinedBlock last = path.peekLast();
        if (last == null || !isWatched(last.material(), oreNames)) {
            return Optional.empty();
        }
        List<MinedBlock> chronological = new ArrayList<>(path);
        return approachTo(chronological, chronological.size() - 1);
    }

    /**
     * Walks backward from {@code oreIndex}, collecting whatever was mined immediately before it in
     * the same dig, and turns that into one reading — or nothing, if there is no "before" close enough
     * to compare it to at all.
     */
    private Optional<ApproachReading> approachTo(List<MinedBlock> chronological, int oreIndex) {
        MinedBlock ore = chronological.get(oreIndex);
        MinedBlock furthestBack = ore;
        int steps = 0;
        for (int index = oreIndex - 1; index >= 0 && steps < MAX_LOOKBACK; index--) {
            MinedBlock candidate = chronological.get(index);
            MinedBlock nextStep = chronological.get(index + 1);
            if (!candidate.world().equals(ore.world())
                    || candidate.distanceTo(nextStep) > MAX_STEP_DISTANCE) {
                break;
            }
            furthestBack = candidate;
            steps++;
        }
        if (steps == 0) {
            return Optional.empty();
        }
        double straightLine = furthestBack.distanceTo(ore);
        int directness = (int) Math.round(100 * Math.min(1.0, straightLine / steps));
        return Optional.of(new ApproachReading(ore, steps, straightLine, directness));
    }

    private static boolean isWatched(String material, Collection<String> oreNames) {
        for (String name : oreNames) {
            if (name != null && name.equalsIgnoreCase(material)) {
                return true;
            }
        }
        return false;
    }
}
