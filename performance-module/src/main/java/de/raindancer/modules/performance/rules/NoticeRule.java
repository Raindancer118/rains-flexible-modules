package de.raindancer.modules.performance.rules;

import java.time.Duration;
import java.util.Set;

/**
 * When staff are told about lag. Once when it starts; after that only when it gets worse or a new
 * place is found — or when the quiet time is over, as a reminder it is still going on.
 */
public final class NoticeRule implements IPerformanceRule {

    /** What staff were last told, and when (milliseconds). */
    public record Last(long at, LagRule.State state, Set<String> findings) {
        public static final Last NEVER = new Last(Long.MIN_VALUE, LagRule.State.HEALTHY, Set.of());

        public Last {
            findings = Set.copyOf(findings);
        }
    }

    private final Duration quiet;

    public NoticeRule(Duration quiet) {
        this.quiet = quiet;
    }

    /** @param findings a key per finding — the same place and kind gives the same key */
    public boolean shouldTell(Last last, long now, LagRule.State state, Set<String> findings) {
        if (state != LagRule.State.LAGGING && state != LagRule.State.SPIKE) {
            return false;
        }
        if (last == Last.NEVER || last.state() == LagRule.State.HEALTHY || last.state() == LagRule.State.STRAINED) {
            return true;
        }
        if (state == LagRule.State.LAGGING && last.state() == LagRule.State.SPIKE) {
            return true;
        }
        if (!last.findings().containsAll(findings)) {
            return true;
        }
        return now - last.at() >= quiet.toMillis();
    }

    @Override
    public String describe() {
        return "tells staff when lag starts, gets worse or shows something new; otherwise once every " + quiet.toMinutes() + " min";
    }
}
