package de.raindancer.modules.oneblock.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressBarTest {

    @Test
    @DisplayName("filled cells follow the share, rounded down, so a bar is never full before it is done")
    void cells() {
        assertThat(ProgressBar.filled(0, 10)).isZero();
        assertThat(ProgressBar.filled(64, 10)).isEqualTo(6);
        assertThat(ProgressBar.filled(99, 10)).isEqualTo(9);
        assertThat(ProgressBar.filled(100, 10)).isEqualTo(10);
        assertThat(ProgressBar.filled(140, 10)).as("clamped").isEqualTo(10);
        assertThat(ProgressBar.filled(-5, 10)).isZero();
    }

    @Test
    @DisplayName("as MiniMessage: the done part and the rest in their own colours")
    void mini() {
        assertThat(ProgressBar.mini(50, 4)).isEqualTo("<green>▮▮</green><dark_gray>▮▮</dark_gray>");
        assertThat(ProgressBar.mini(100, 3)).isEqualTo("<green>▮▮▮</green>");
    }
}
