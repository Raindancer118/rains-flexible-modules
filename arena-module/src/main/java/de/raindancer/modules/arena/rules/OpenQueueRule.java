package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.model.QueueEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Which fights in the queue still wait for more people — what the hub is told about, so somebody can join. */
public final class OpenQueueRule implements IArenaRule {

    /** @param waiting oldest first */
    public record Open(MatchSpec spec, List<UUID> waiting, int needed) {
    }

    public List<Open> open(List<QueueEntry> entries) {
        Map<String, List<QueueEntry>> byKey = new LinkedHashMap<>();
        entries.stream().sorted(Comparator.comparingLong(QueueEntry::since))
                .forEach(entry -> byKey.computeIfAbsent(entry.spec().queueKey(), key -> new ArrayList<>()).add(entry));
        List<Open> open = new ArrayList<>();
        for (List<QueueEntry> same : byKey.values()) {
            MatchSpec spec = same.getFirst().spec();
            if (spec.players() > 1 && same.size() < spec.players()) {
                open.add(new Open(spec, same.stream().map(QueueEntry::player).toList(), spec.players()));
            }
        }
        return open;
    }

    /**
     * Names one exact fight — mode, kit, rounds, stake, team size, waves — for a [Join] button, so a click joins what
     * the line said and not whatever that player switched to since.
     */
    public static String fingerprint(MatchSpec spec) {
        return String.format("%08x", spec.queueKey().hashCode());
    }

    /** The open queues somebody could join — not the one they are waiting in. */
    public List<Open> forViewer(List<Open> open, UUID viewer) {
        return open.stream().filter(each -> !each.waiting().contains(viewer)).toList();
    }

    @Override
    public String describe() {
        return "which queued fights still wait for more people";
    }
}
