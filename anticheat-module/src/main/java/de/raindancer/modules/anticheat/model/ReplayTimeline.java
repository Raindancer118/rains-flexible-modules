package de.raindancer.modules.anticheat.model;

import java.util.List;

/**
 * A replay as something to play back: where the player was at any moment of it, between two recorded
 * moves as well, so a figure walking the path moves as smoothly and as fast as they did. The last frame
 * is the moment the check alerted.
 */
public final class ReplayTimeline {

    private final List<ReplayFrame> frames;
    private final long start;

    public ReplayTimeline(List<ReplayFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("a replay needs at least one frame");
        }
        this.frames = List.copyOf(frames);
        this.start = this.frames.getFirst().atMillis();
    }

    /** How long it lasted. */
    public long millis() {
        return frames.getLast().atMillis() - start;
    }

    /** Where they were {@code millis} after the first frame; the first or last frame outside the replay. */
    public ReplayFrame at(long millis) {
        long when = start + millis;
        if (when <= start) {
            return frames.getFirst();
        }
        for (int i = 1; i < frames.size(); i++) {
            ReplayFrame to = frames.get(i);
            if (to.atMillis() >= when) {
                ReplayFrame from = frames.get(i - 1);
                long span = Math.max(1, to.atMillis() - from.atMillis());
                double t = (double) (when - from.atMillis()) / span;
                return new ReplayFrame(when, to.world(), lerp(from.x(), to.x(), t), lerp(from.y(), to.y(), t),
                        lerp(from.z(), to.z(), t), turn(from.yaw(), to.yaw(), t), (float) lerp(from.pitch(), to.pitch(), t),
                        (t < 0.5 ? from : to).onGround(), (t < 0.5 ? from : to).slot(), (t < 0.5 ? from : to).sneaking(),
                        (t < 0.5 ? from : to).gliding());
            }
        }
        return frames.getLast();
    }

    /** Whether this moment is the alert — the end of the replay. */
    public boolean isAlert(long millis) {
        return millis >= millis();
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** The short way round: from 170° to -170° is twenty degrees, not three hundred and forty. */
    private static float turn(float from, float to, double t) {
        float delta = ((to - from) % 360 + 540) % 360 - 180;
        float yaw = (float) (from + delta * t);
        return ((yaw + 180) % 360 + 360) % 360 - 180;
    }
}
