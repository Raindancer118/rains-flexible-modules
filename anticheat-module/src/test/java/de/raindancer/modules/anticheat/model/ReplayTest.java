package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayTest {

    @Test
    @DisplayName("the recorder keeps the newest frames only, oldest first")
    void ring() {
        ReplayRecorder recorder = new ReplayRecorder(3);
        for (int i = 0; i < 5; i++) {
            recorder.record(new ReplayFrame(i * 50L, "world", i, 64, 0, 0, 0, true));
        }
        List<ReplayFrame> frames = recorder.frames();
        assertThat(frames).extracting(ReplayFrame::x).containsExactly(2.0, 3.0, 4.0);
    }

    @Test
    @DisplayName("a frame survives being written down and read back")
    void encode() {
        ReplayFrame frame = new ReplayFrame(1234L, "world_nether", 1.25, -3.5, 7.75, 91.5f, -12.25f, false);
        assertThat(ReplayFrame.decode(frame.encode())).contains(frame);
        assertThat(ReplayFrame.decode("garbage")).isEmpty();
    }

    @Test
    @DisplayName("each player keeps only their newest replays")
    void keepsNewest() {
        Replays replays = new Replays(2);
        java.util.UUID who = java.util.UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            replays.add(who, new Replays.Replay(i, "fly", "detail " + i, List.of()));
        }
        assertThat(replays.of(who)).extracting(Replays.Replay::detail).containsExactly("detail 2", "detail 1");
    }
}
