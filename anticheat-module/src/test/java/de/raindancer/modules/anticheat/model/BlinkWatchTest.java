package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkWatchTest {

    private final BlinkWatch watch = new BlinkWatch();

    @Test
    @DisplayName("a client that ticks on without a move for a second and a half is holding its moves back")
    void holding() {
        String said = null;
        for (int tick = 0; tick < 40 && said == null; tick++) {
            said = watch.tickEnd().orElse(null);
        }
        assertThat(said).contains("ticks").contains("without a move");
    }

    @Test
    @DisplayName("vanilla's position reminder every twenty ticks is enough")
    void reminder() {
        for (int tick = 0; tick < 400; tick++) {
            if (tick % 20 == 0) {
                watch.moved();
            }
            assertThat(watch.tickEnd()).isEmpty();
        }
    }

    @Test
    @DisplayName("one hold is one flag, not one a tick")
    void once() {
        int flags = 0;
        for (int tick = 0; tick < 200; tick++) {
            flags += watch.tickEnd().isPresent() ? 1 : 0;
        }
        assertThat(flags).isEqualTo(1);
        watch.moved();
        for (int tick = 0; tick < 40; tick++) {
            flags += watch.tickEnd().isPresent() ? 1 : 0;
        }
        assertThat(flags).as("a new hold after a move").isEqualTo(2);
    }

    @Test
    @DisplayName("while excused — dead, just teleported — nothing is counted")
    void excused() {
        watch.excused(true);
        for (int tick = 0; tick < 100; tick++) {
            assertThat(watch.tickEnd()).isEmpty();
        }
        watch.excused(false);
        assertThat(watch.tickEnd()).isEmpty();
    }
}
