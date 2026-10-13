package de.raindancer.modules.twitch.rules;

import de.raindancer.modules.twitch.model.Observation;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.model.StreamInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * When to say "live" and when to say "over". Twitch flickers — a stream drops for a minute and comes back under the
 * same id — and a failed look says nothing, so:
 * <ul>
 *   <li>a stream is announced once, by its id, and only if it began recently — a server that starts mid-stream stays
 *       quiet about a stream that has been on for hours;</li>
 *   <li>a stream is over only after several looks in a row find the channel offline, and only if it was announced;</li>
 *   <li>a look that failed changes nothing.</li>
 * </ul>
 */
public final class LiveTrackerRule implements ITwitchRule {

    private final Set<String> announced = new HashSet<>();
    private StreamInfo current;
    private int offlineLooks;

    /** @param alreadyAnnounced stream ids told before a restart, so a stream still running is not announced again */
    public LiveTrackerRule(Set<String> alreadyAnnounced) {
        announced.addAll(alreadyAnnounced);
    }

    public synchronized List<StreamEvent> observe(Observation seen, Instant now, Duration freshWithin, int endAfterLooks) {
        List<StreamEvent> events = new ArrayList<>();
        if (seen instanceof Observation.Live live) {
            offlineLooks = 0;
            StreamInfo stream = live.stream();
            boolean isNew = current == null || !current.id().equals(stream.id());
            current = stream;
            if (isNew && !announced.contains(stream.id())
                    && !stream.startedAt().isBefore(now.minus(freshWithin))) {
                announced.add(stream.id());
                events.add(new StreamEvent.WentLive(stream));
            }
        } else if (seen == Observation.OFFLINE && current != null) {
            offlineLooks++;
            if (offlineLooks >= Math.max(1, endAfterLooks)) {
                if (announced.contains(current.id())) {
                    events.add(new StreamEvent.Ended(current,
                            Duration.between(current.startedAt(), now).abs()));
                }
                current = null;
                offlineLooks = 0;
            }
        }
        return events;
    }

    /** Ids told so far, for keeping across a restart. */
    public synchronized Set<String> announcedIds() {
        return Set.copyOf(announced);
    }

    public synchronized Optional<StreamInfo> running() {
        return Optional.ofNullable(current);
    }

    @Override
    public String describe() {
        return "when a stream is announced as live and when as over, through flicker and failed looks";
    }
}
