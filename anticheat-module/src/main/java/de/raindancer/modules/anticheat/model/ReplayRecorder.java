package de.raindancer.modules.anticheat.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The last few seconds of one player's movement, always running, frozen into a replay when a check alerts. */
public final class ReplayRecorder {

    private final int capacity;
    private final Deque<ReplayFrame> frames = new ArrayDeque<>();

    public ReplayRecorder(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void record(ReplayFrame frame) {
        frames.addLast(frame);
        while (frames.size() > capacity) {
            frames.removeFirst();
        }
    }

    public synchronized List<ReplayFrame> frames() {
        return List.copyOf(frames);
    }

    public synchronized void clear() {
        frames.clear();
    }
}
