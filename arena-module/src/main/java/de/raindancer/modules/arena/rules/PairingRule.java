package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.QueueEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which waiting players make a fight. Same kind of fight only (the queue key: mode, kit, rounds, stake, team size),
 * oldest first. A free-for-all waits for the most it takes, or starts with fewer once the third has waited long enough.
 */
public final class PairingRule implements IArenaRule {

    /** A fight that can start: the spec, and who is on which side. */
    public record Group(MatchSpec spec, List<Set<UUID>> sides) {

        public Set<UUID> players() {
            Set<UUID> all = new HashSet<>();
            sides.forEach(all::addAll);
            return all;
        }
    }

    public List<Group> groups(List<QueueEntry> waiting, long now, long ffaWaitMillis) {
        Map<String, List<QueueEntry>> byKey = new LinkedHashMap<>();
        for (QueueEntry entry : waiting) {
            byKey.computeIfAbsent(entry.spec().queueKey(), key -> new ArrayList<>()).add(entry);
        }
        List<Group> groups = new ArrayList<>();
        for (List<QueueEntry> same : byKey.values()) {
            MatchSpec spec = same.getFirst().spec();
            int taken = 0;
            while (true) {
                List<QueueEntry> left = same.subList(taken, same.size());
                int size = sizeFor(spec, left, now, ffaWaitMillis);
                if (size == 0) {
                    break;
                }
                groups.add(new Group(spec, sides(spec, left.subList(0, size))));
                taken += size;
            }
        }
        return groups;
    }

    private static int sizeFor(MatchSpec spec, List<QueueEntry> left, long now, long ffaWaitMillis) {
        if (spec.mode().isFreeForAll()) {
            Mode mode = spec.mode();
            if (left.size() >= mode.most()) {
                return mode.most();
            }
            if (left.size() >= mode.fewest() && now - left.get(mode.fewest() - 1).since() >= ffaWaitMillis) {
                return left.size();
            }
            return 0;
        }
        return left.size() >= spec.players() ? spec.players() : 0;
    }

    private static List<Set<UUID>> sides(MatchSpec spec, List<QueueEntry> entries) {
        List<Set<UUID>> sides = new ArrayList<>();
        if (spec.mode() == Mode.WAVES) {
            Set<UUID> together = new HashSet<>();
            entries.forEach(entry -> together.add(entry.player()));
            sides.add(together);
        } else if (spec.mode() == Mode.TEAMS) {
            sides.add(new HashSet<>());
            sides.add(new HashSet<>());
            for (int index = 0; index < entries.size(); index++) {
                sides.get(index % 2).add(entries.get(index).player());
            }
        } else {
            entries.forEach(entry -> sides.add(Set.of(entry.player())));
        }
        return sides;
    }

    @Override
    public String describe() {
        return "the same kind of fight, oldest first";
    }
}
