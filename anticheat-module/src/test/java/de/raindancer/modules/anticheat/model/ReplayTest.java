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

    @Test
    @DisplayName("a frame keeps the hotbar slot held and whether they sneaked or glided")
    void stance() {
        ReplayFrame frame = new ReplayFrame(1234L, "world", 1, 2, 3, 4, 5, false, 6, true, false);
        assertThat(ReplayFrame.decode(frame.encode())).contains(frame);
    }

    @Test
    @DisplayName("frames written before stances were kept still read, holding nothing known")
    void oldFrames() {
        assertThat(ReplayFrame.decode("1234;world;1.000;2.000;3.000;4.00;5.00;true")).hasValueSatisfying(frame -> {
            assertThat(frame.slot()).isEqualTo(-1);
            assertThat(frame.sneaking()).isFalse();
        });
    }

    @Test
    @DisplayName("a replay keeps the loadout they had at the alert")
    void loadout() {
        ReplayLoadout gear = new ReplayLoadout(java.util.Arrays.asList("helmet", null, "legs", "boots"), "shield",
                java.util.Arrays.asList("sword", null, null, null, null, null, null, null, "pearls"));
        Replays.Replay replay = new Replays.Replay(1, "fly", "detail", List.of(), gear);
        assertThat(replay.loadout().hotbar()).hasSize(9);
        assertThat(new Replays.Replay(1, "fly", "detail", List.of()).loadout()).isNull();
    }
}
