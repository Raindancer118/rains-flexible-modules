package de.raindancer.modules.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SpeedrunTimelineTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    @DisplayName("a milestone is split once, by whoever got there first")
    void firstSplitOnly() {
        SpeedrunTimeline timeline = new SpeedrunTimeline();

        assertThat(timeline.split("enter-nether", Duration.ofMinutes(4), ALICE)).isTrue();
        assertThat(timeline.split("enter-nether", Duration.ofMinutes(5), BOB)).isFalse();

        assertThat(timeline.splitAt("enter-nether").orElseThrow().who()).isEqualTo(ALICE);
        assertThat(timeline.splitAt("enter-nether").orElseThrow().at()).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("a split cannot sneak in through record()")
    void splitsOnlyThroughSplit() {
        assertThatCode(() -> new SpeedrunTimeline().record(SpeedrunTimeline.Kind.SPLIT, Duration.ZERO, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the session writes its pauses, clock edits, roster changes and finish onto the timeline")
    void sessionKeepsTheRecord() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        session.pauseForEmptyRoster();
        session.resume();
        session.setElapsed(Duration.ofMinutes(10));
        session.addParticipant(UUID.randomUUID());
        session.removeParticipant(BOB);
        session.finish("advancement:minecraft:end/kill_dragon");

        assertThat(session.timeline().entries()).extracting(SpeedrunTimeline.Entry::kind).containsExactly(
                SpeedrunTimeline.Kind.PAUSE, SpeedrunTimeline.Kind.UNPAUSE, SpeedrunTimeline.Kind.CLOCK_EDIT,
                SpeedrunTimeline.Kind.JOINED, SpeedrunTimeline.Kind.LEFT, SpeedrunTimeline.Kind.FINISH);
        assertThat(session.timeline().of(SpeedrunTimeline.Kind.CLOCK_EDIT).getFirst().detail())
                .as("what the clock read before").isEqualTo("0:00");
        assertThat(session.timeline().of(SpeedrunTimeline.Kind.FINISH).getFirst().detail())
                .isEqualTo("advancement:minecraft:end/kill_dragon");
    }

    @Test
    @DisplayName("a pause that did nothing is not on the record")
    void noOpsAreNotRecorded() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.pauseForEmptyRoster();   // not started yet
        session.start();
        session.resume();                // not paused

        assertThat(session.timeline().entries()).isEmpty();
    }
}
