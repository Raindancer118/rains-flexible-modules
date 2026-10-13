package de.raindancer.modules.twitch.rules;

import de.raindancer.modules.twitch.model.Observation;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.model.StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTrackerRuleTest {

    private static final Duration FRESH = Duration.ofMinutes(15);
    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");

    private static StreamInfo stream(String id, Instant startedAt) {
        return new StreamInfo(id, "lillyyxoxo_", "Chill and build", "Minecraft", startedAt, 12);
    }

    private static Observation live(String id, Instant startedAt) {
        return new Observation.Live(stream(id, startedAt));
    }

    private final LiveTrackerRule tracker = new LiveTrackerRule(Set.of());

    @Test
    @DisplayName("a stream that just began is announced once, however many looks find it")
    void announcedOnce() {
        List<StreamEvent> first = tracker.observe(live("1", T0), T0.plusSeconds(60), FRESH, 3);
        List<StreamEvent> second = tracker.observe(live("1", T0), T0.plusSeconds(120), FRESH, 3);

        assertThat(first).hasSize(1).first().isInstanceOf(StreamEvent.WentLive.class);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("a stream that has been on for hours when the server starts is not announced, and not announced over either")
    void staleStreamIsQuiet() {
        Instant now = T0.plus(Duration.ofHours(3));

        assertThat(tracker.observe(live("1", T0), now, FRESH, 3)).isEmpty();
        for (int look = 0; look < 5; look++) {
            assertThat(tracker.observe(Observation.OFFLINE, now.plusSeconds(60L * (look + 1)), FRESH, 3)).isEmpty();
        }
    }

    @Test
    @DisplayName("the end is told after enough looks in a row, with how long it lasted")
    void endedAfterSeveralLooks() {
        tracker.observe(live("1", T0), T0.plusSeconds(30), FRESH, 3);

        assertThat(tracker.observe(Observation.OFFLINE, T0.plus(Duration.ofMinutes(90)), FRESH, 3)).isEmpty();
        assertThat(tracker.observe(Observation.OFFLINE, T0.plus(Duration.ofMinutes(91)), FRESH, 3)).isEmpty();
        List<StreamEvent> ended = tracker.observe(Observation.OFFLINE, T0.plus(Duration.ofMinutes(92)), FRESH, 3);

        assertThat(ended).hasSize(1);
        StreamEvent.Ended end = (StreamEvent.Ended) ended.getFirst();
        assertThat(end.lasted()).isEqualTo(Duration.ofMinutes(92));
        assertThat(tracker.running()).isEmpty();
    }

    @Test
    @DisplayName("a stream that drops for a moment and comes back is neither over nor announced again")
    void flicker() {
        tracker.observe(live("1", T0), T0.plusSeconds(30), FRESH, 3);
        tracker.observe(Observation.OFFLINE, T0.plusSeconds(90), FRESH, 3);
        tracker.observe(Observation.OFFLINE, T0.plusSeconds(150), FRESH, 3);

        assertThat(tracker.observe(live("1", T0), T0.plusSeconds(210), FRESH, 3)).isEmpty();
        assertThat(tracker.observe(Observation.OFFLINE, T0.plusSeconds(270), FRESH, 3)).isEmpty();
        assertThat(tracker.observe(Observation.OFFLINE, T0.plusSeconds(330), FRESH, 3)).isEmpty();
    }

    @Test
    @DisplayName("a failed look changes nothing: it neither ends a stream nor counts towards ending it")
    void unknownChangesNothing() {
        tracker.observe(live("1", T0), T0.plusSeconds(30), FRESH, 2);

        tracker.observe(Observation.OFFLINE, T0.plusSeconds(90), FRESH, 2);
        assertThat(tracker.observe(Observation.UNKNOWN, T0.plusSeconds(150), FRESH, 2)).isEmpty();
        assertThat(tracker.running()).isPresent();
        assertThat(tracker.observe(Observation.OFFLINE, T0.plusSeconds(210), FRESH, 2)).hasSize(1);
    }

    @Test
    @DisplayName("a new stream after an ended one is announced again")
    void nextStream() {
        tracker.observe(live("1", T0), T0.plusSeconds(30), FRESH, 1);
        tracker.observe(Observation.OFFLINE, T0.plusSeconds(90), FRESH, 1);
        Instant later = T0.plus(Duration.ofHours(5));

        List<StreamEvent> again = tracker.observe(live("2", later), later.plusSeconds(30), FRESH, 1);

        assertThat(again).hasSize(1).first().isInstanceOf(StreamEvent.WentLive.class);
    }

    @Test
    @DisplayName("after a restart, a stream already announced is not announced again but is still announced over")
    void restart() {
        LiveTrackerRule restarted = new LiveTrackerRule(Set.of("1"));

        assertThat(restarted.observe(live("1", T0), T0.plus(Duration.ofMinutes(40)), FRESH, 1)).isEmpty();
        assertThat(restarted.observe(Observation.OFFLINE, T0.plus(Duration.ofMinutes(50)), FRESH, 1)).hasSize(1);
    }

    @Test
    @DisplayName("the ids to keep across a restart are the ones announced")
    void ids() {
        tracker.observe(live("7", T0), T0.plusSeconds(30), FRESH, 3);

        assertThat(tracker.announcedIds()).containsExactly("7");
    }
}
