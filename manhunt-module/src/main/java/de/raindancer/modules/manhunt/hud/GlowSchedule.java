package de.raindancer.modules.manhunt.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * When the Runners glow ({@code glowing-runners-every-minutes}): a warning ten seconds before, then
 * the glow, every period. Asked for the seconds a tick crossed rather than the second it landed on,
 * so a lagging server that skips a second does not skip the glow with it.
 */
public final class GlowSchedule {

    static final int WARNING_SECONDS = 10;

    public enum Cue { WARN, GLOW }

    private GlowSchedule() {
    }

    /** What falls in {@code (fromSecond, toSecond]} of the hunt. */
    public static List<Cue> between(long fromSecond, long toSecond, int everyMinutes) {
        List<Cue> cues = new ArrayList<>();
        if (everyMinutes <= 0) {
            return cues;
        }
        long period = everyMinutes * 60L;
        for (long second = Math.max(1, fromSecond + 1); second <= toSecond; second++) {
            if (period > WARNING_SECONDS && second % period == period - WARNING_SECONDS) {
                cues.add(Cue.WARN);
            }
            if (second % period == 0) {
                cues.add(Cue.GLOW);
            }
        }
        return cues;
    }
}
