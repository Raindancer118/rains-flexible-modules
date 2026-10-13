package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.twitch.TwitchSettings;
import de.raindancer.modules.twitch.model.Observation;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.rules.LiveTrackerRule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** One look at Twitch after another; what the tracker says is worth telling goes to the announcer. */
public final class TwitchPoller {

    private static final int KEPT_IDS = 20;

    private final TwitchClient client;
    private final LiveTrackerRule tracker;
    private final Announcer announcer;
    private final Supplier<TwitchSettings> settings;
    private final YamlStore memory;
    private final LogChannel log;
    private volatile Observation last = Observation.UNKNOWN;
    private volatile Instant lastLook;

    public TwitchPoller(TwitchClient client, LiveTrackerRule tracker, Announcer announcer,
                        Supplier<TwitchSettings> settings, YamlStore memory, LogChannel log) {
        this.client = client;
        this.tracker = tracker;
        this.announcer = announcer;
        this.settings = settings;
        this.memory = memory;
        this.log = log;
    }

    /** What was announced before this start, so a stream still running is not announced again. */
    public static java.util.Set<String> remembered(YamlStore memory) {
        return new java.util.HashSet<>(memory.read().getStringList("announced"));
    }

    /** Off the server's threads. */
    public void poll() {
        TwitchSettings live = settings.get();
        if (!live.enabled()) {
            return;
        }
        Observation seen = client.look(live.channelClean());
        last = seen;
        lastLook = Instant.now();
        List<StreamEvent> events = tracker.observe(seen, Instant.now(), live.fresh(), live.endAfterLooks());
        for (StreamEvent event : events) {
            announcer.announce(event);
        }
        if (!events.isEmpty()) {
            List<String> ids = new ArrayList<>(tracker.announcedIds());
            List<String> kept = ids.subList(Math.max(0, ids.size() - KEPT_IDS), ids.size());
            if (!memory.update(yaml -> yaml.set("announced", new ArrayList<>(kept)))) {
                log.warn("The announced streams could not be written; a restart may announce the running one again.");
            }
        }
    }

    public Observation last() {
        return last;
    }

    public Instant lastLook() {
        return lastLook;
    }

    public LiveTrackerRule tracker() {
        return tracker;
    }
}
