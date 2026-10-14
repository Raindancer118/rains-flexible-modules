package de.raindancer.modules.twitch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who earns which Twitch advancement on this server, from what it hears: the stream going live and ending,
 * players joining, leaving, dying and standing near the streamer. Decides only; granting is the caller's.
 * Kept per server — every server of the network hears the announcements and keeps its own.
 */
public final class ViewerTracker {

    public static final String TUNED_IN = "tuned_in";
    public static final String HYPE_TRAIN = "hype_train";
    public static final String DAY_ONE = "day_one";
    public static final String REGULAR = "regular";
    public static final String AFTERPARTY = "afterparty";
    public static final String CAUGHT_ON_STREAM = "caught_on_stream";
    public static final String CLIP_THAT = "clip_that";

    public static final long HYPE_MILLIS = 5 * 60_000L;
    public static final int CAUGHT_MINUTES = 10;

    public record Award(UUID player, String advancement) {
    }

    private String stream;
    private long startedAt;
    private final Set<UUID> wholeStream = new HashSet<>();
    private final Map<UUID, Integer> minutesNear = new HashMap<>();

    public synchronized boolean isLive() {
        return stream != null;
    }

    /** The live stream's id, null when nothing is live. */
    public synchronized String stream() {
        return stream;
    }

    public synchronized List<Award> live(String id, long started, Set<UUID> online, long now) {
        stream = id == null || id.isBlank() ? "stream@" + started : id;
        startedAt = started > 0 ? started : now;
        wholeStream.clear();
        wholeStream.addAll(online);
        minutesNear.clear();
        List<Award> awards = new ArrayList<>();
        for (UUID player : online) {
            awards.add(new Award(player, TUNED_IN));
            awards.add(new Award(player, REGULAR));
        }
        return awards;
    }

    public synchronized List<Award> joined(UUID player, long now) {
        if (stream == null) {
            return List.of();
        }
        List<Award> awards = new ArrayList<>();
        if (now - startedAt <= HYPE_MILLIS) {
            awards.add(new Award(player, HYPE_TRAIN));
        }
        awards.add(new Award(player, REGULAR));
        return awards;
    }

    public synchronized void quit(UUID player) {
        wholeStream.remove(player);
    }

    public synchronized List<Award> ended(Set<UUID> online) {
        List<Award> awards = new ArrayList<>();
        if (stream != null) {
            for (UUID player : online) {
                awards.add(new Award(player, AFTERPARTY));
                if (wholeStream.contains(player)) {
                    awards.add(new Award(player, DAY_ONE));
                }
            }
        }
        stream = null;
        wholeStream.clear();
        minutesNear.clear();
        return awards;
    }

    /** Called once a minute with whoever is near the streamer right now. */
    public synchronized List<Award> minuteNear(Set<UUID> near) {
        if (stream == null) {
            return List.of();
        }
        List<Award> awards = new ArrayList<>();
        for (UUID player : near) {
            if (minutesNear.merge(player, 1, Integer::sum) == CAUGHT_MINUTES) {
                awards.add(new Award(player, CAUGHT_ON_STREAM));
            }
        }
        return awards;
    }

    public synchronized List<Award> diedNear(UUID player) {
        return stream == null ? List.of() : List.of(new Award(player, CLIP_THAT));
    }
}
