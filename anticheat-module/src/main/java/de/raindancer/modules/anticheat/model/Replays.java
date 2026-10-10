package de.raindancer.modules.anticheat.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Frozen replays, the newest few per player. */
public final class Replays {

    /** What happened in the seconds before an alert. */
    public record Replay(long atMillis, String check, String detail, List<ReplayFrame> frames) {
    }

    private final int perPlayer;
    private final Map<UUID, Deque<Replay>> replays = new ConcurrentHashMap<>();

    public Replays(int perPlayer) {
        this.perPlayer = Math.max(1, perPlayer);
    }

    public void add(UUID who, Replay replay) {
        Deque<Replay> theirs = replays.computeIfAbsent(who, ignored -> new ArrayDeque<>());
        synchronized (theirs) {
            theirs.addFirst(replay);
            while (theirs.size() > perPlayer) {
                theirs.removeLast();
            }
        }
    }

    /** Newest first. */
    public List<Replay> of(UUID who) {
        Deque<Replay> theirs = replays.get(who);
        if (theirs == null) {
            return List.of();
        }
        synchronized (theirs) {
            return new ArrayList<>(theirs);
        }
    }

    public Set<UUID> everybody() {
        return Set.copyOf(replays.keySet());
    }

    public void clear(UUID who) {
        replays.remove(who);
    }

    public void restore(UUID who, List<Replay> saved) {
        Deque<Replay> theirs = new ArrayDeque<>(saved);
        replays.put(who, theirs);
    }
}
