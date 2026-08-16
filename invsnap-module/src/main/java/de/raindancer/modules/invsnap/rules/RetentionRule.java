package de.raindancer.modules.invsnap.rules;

import de.raindancer.modules.invsnap.model.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which snapshots survive an addition, for one player's rolling window.
 *
 * <p>Newest kept, oldest dropped first — the shape a "last two hours" history is expected to have.
 * Sorted by {@link Snapshot#takenAt()} rather than trusting arrival order, since a store's own list
 * is only ever as ordered as whatever wrote the file last.
 */
public final class RetentionRule implements IInvSnapRule {

    /**
     * @param existing       what is already kept for this player, in any order
     * @param fresh          the new snapshot to add, or {@code null} to only trim
     * @param retentionCount how many to keep; anything below one keeps exactly one
     * @return the snapshots to keep, oldest first, at most {@code retentionCount} of them
     */
    public List<Snapshot> applying(List<Snapshot> existing, Snapshot fresh, int retentionCount) {
        List<Snapshot> combined = new ArrayList<>(existing == null ? List.of() : existing);
        if (fresh != null) {
            combined.add(fresh);
        }
        combined.sort(Comparator.comparing(Snapshot::takenAt));

        int keep = Math.max(1, retentionCount);
        if (combined.size() > keep) {
            combined = new ArrayList<>(combined.subList(combined.size() - keep, combined.size()));
        }
        return List.copyOf(combined);
    }

    @Override
    public String describe() {
        return "keeps only the newest N snapshots per player, oldest dropped first";
    }
}
