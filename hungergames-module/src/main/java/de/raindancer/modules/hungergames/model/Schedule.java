package de.raindancer.modules.hungergames.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pure timetable behind a round's scheduled events — supply drops, sponsor-beacon spawns, anything
 * that is meant to happen at a fixed point in game time rather than in response to something a player did.
 *
 * <h2>Why "due" is a question and not a callback</h2>
 * Nothing here fires anything. It is asked, on a tick, "which entries in this timetable are due and have
 * not already fired", and it answers with indices. The caller is the one that remembers which indices it
 * has already acted on — one set per timetable, held wherever the timetable is used — and persists that
 * set the same way {@code store.GameSession} persists everything else about a round.
 *
 * <p>That split is what makes a restart safe. An event whose firing and its "have I fired" bit live in the
 * same place can lose the bit without losing the firing — the server goes down half a second after a
 * supply drop and comes back up believing it never happened — and the fix is not a more careful save, it
 * is not letting the two facts be two different writes in the first place. Here the timetable is a pure
 * function of three plain values, so replaying it after a restart is exactly as safe as replaying it any
 * other tick: an index the caller has already recorded as fired never comes back, and one that fell due
 * while the server was offline is caught up on the very next look rather than being lost to the outage.
 */
public final class Schedule {

    private Schedule() {
    }

    /**
     * @param timetable the scheduled game-time points, in order — position is the index
     * @param triggered indices already fired, as recorded by the caller
     * @param elapsed   the current (virtual) game time
     * @return indices that are due now and not yet in {@code triggered}, ascending
     */
    public static List<Integer> dueIndices(List<Duration> timetable, Set<Integer> triggered, Duration elapsed) {
        List<Integer> due = new ArrayList<>();
        for (int i = 0; i < timetable.size(); i++) {
            if (!triggered.contains(i) && timetable.get(i).compareTo(elapsed) <= 0) {
                due.add(i);
            }
        }
        return due;
    }
}
