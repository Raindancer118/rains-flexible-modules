package de.raindancer.modules.twitch.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ViewerTrackerTest {

    private static final UUID ADA = UUID.nameUUIDFromBytes("ada".getBytes());
    private static final UUID BO = UUID.nameUUIDFromBytes("bo".getBytes());
    private static final long MINUTE = 60_000;

    private static List<String> of(List<ViewerTracker.Award> awards, UUID player) {
        return awards.stream().filter(award -> award.player().equals(player)).map(ViewerTracker.Award::advancement).toList();
    }

    @Test
    @DisplayName("online when it goes live: tuned in; and the stream counts towards Regular")
    void tunedIn() {
        ViewerTracker tracker = new ViewerTracker();
        List<ViewerTracker.Award> awards = tracker.live("s1", 0, Set.of(ADA), 0);
        assertThat(of(awards, ADA)).containsExactlyInAnyOrder(ViewerTracker.TUNED_IN, ViewerTracker.REGULAR);
        assertThat(tracker.isLive()).isTrue();
        assertThat(tracker.stream()).isEqualTo("s1");
    }

    @Test
    @DisplayName("joining within five minutes of going live is the hype train; later only counts the stream")
    void hypeTrain() {
        ViewerTracker tracker = new ViewerTracker();
        tracker.live("s1", 0, Set.of(), 0);
        assertThat(of(tracker.joined(ADA, 5 * MINUTE), ADA)).contains(ViewerTracker.HYPE_TRAIN, ViewerTracker.REGULAR);
        assertThat(of(tracker.joined(BO, 5 * MINUTE + 1), BO)).containsExactly(ViewerTracker.REGULAR);
        assertThat(new ViewerTracker().joined(ADA, 0)).as("nothing live, nothing to earn").isEmpty();
    }

    @Test
    @DisplayName("there from start to end is day one; online at the end is the afterparty; leaving breaks day one")
    void ending() {
        ViewerTracker tracker = new ViewerTracker();
        tracker.live("s1", 0, Set.of(ADA, BO), 0);
        tracker.quit(BO);
        tracker.joined(BO, 10 * MINUTE);
        List<ViewerTracker.Award> awards = tracker.ended(Set.of(ADA, BO));
        assertThat(of(awards, ADA)).containsExactlyInAnyOrder(ViewerTracker.DAY_ONE, ViewerTracker.AFTERPARTY);
        assertThat(of(awards, BO)).containsExactly(ViewerTracker.AFTERPARTY);
        assertThat(tracker.isLive()).isFalse();
    }

    @Test
    @DisplayName("ten minutes near the streamer while live is caught on stream; dying near them is clip that")
    void nearTheStreamer() {
        ViewerTracker tracker = new ViewerTracker();
        assertThat(tracker.minuteNear(Set.of(ADA))).as("not live").isEmpty();
        tracker.live("s1", 0, Set.of(), 0);
        for (int minute = 1; minute < 10; minute++) {
            assertThat(tracker.minuteNear(Set.of(ADA))).isEmpty();
        }
        assertThat(of(tracker.minuteNear(Set.of(ADA)), ADA)).containsExactly(ViewerTracker.CAUGHT_ON_STREAM);
        assertThat(of(tracker.diedNear(BO), BO)).containsExactly(ViewerTracker.CLIP_THAT);
        tracker.ended(Set.of());
        assertThat(tracker.diedNear(BO)).isEmpty();
    }
}
