package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlowStateTest {

    @Test
    @DisplayName("a press is graced for its first moves only")
    void graceEnds() {
        SlowState state = new SlowState();
        state.move(true, 1);
        assertThat(state.graced()).isTrue();
        state.move(true, 1);
        assertThat(state.graced()).isTrue();
        state.move(true, 1);
        assertThat(state.graced()).isFalse();
    }

    @Test
    @DisplayName("a player tapping shift now and then is graced every time")
    void honestTaps() {
        SlowState state = new SlowState();
        for (int tap = 0; tap < 6; tap++) {
            state.move(true, 1);
            assertThat(state.graced()).as("tap %d", tap).isTrue();
            for (int t = 0; t < 14; t++) {
                state.move(false, 1);
            }
        }
        assertThat(state.rapid()).isFalse();
    }

    @Test
    @DisplayName("a client pressing again every other tick is graced a few times, then slowed from the first move, and reported as rapid")
    void toggler() {
        SlowState state = new SlowState();
        int graced = 0;
        for (int tick = 0; tick < 20; tick++) {
            state.move(tick % 2 == 0, 1);
            if (tick % 2 == 0 && state.graced()) {
                graced++;
            }
        }
        assertThat(graced).isEqualTo(SlowState.GRACES_PER_WINDOW);
        assertThat(state.rapid()).isTrue();
        state.move(false, 1);
        state.move(true, 1);
        assertThat(state.graced()).as("the budget is spent").isFalse();
    }

    @Test
    @DisplayName("the budget refills as the window moves on")
    void refills() {
        SlowState state = new SlowState();
        for (int tick = 0; tick < 10; tick++) {
            state.move(tick % 2 == 0, 1);
        }
        for (int t = 0; t < SlowState.WINDOW + 2; t++) {
            state.move(false, 1);
        }
        state.move(true, 1);
        assertThat(state.graced()).isTrue();
        assertThat(state.rapid()).isFalse();
    }

    @Test
    @DisplayName("a move covering several ticks moves the clock by all of them")
    void ticks() {
        SlowState state = new SlowState();
        state.move(true, 1);
        state.move(true, 5);
        assertThat(state.graced()).isFalse();
    }
}
