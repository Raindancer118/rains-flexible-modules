package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiningWindowTest {

    @Nested
    @DisplayName("filling up")
    class FillingUp {

        @Test
        @DisplayName("starts empty")
        void startsEmpty() {
            MiningWindow window = new MiningWindow(10);

            assertThat(window.totalCount()).isZero();
            assertThat(window.oreCount()).isZero();
            assertThat(window.ratio()).isZero();
        }

        @Test
        @DisplayName("counts ore and non-ore separately")
        void countsBoth() {
            MiningWindow window = new MiningWindow(10);
            window.record(true);
            window.record(false);
            window.record(false);

            assertThat(window.totalCount()).isEqualTo(3);
            assertThat(window.oreCount()).isEqualTo(1);
            assertThat(window.ratio()).isEqualTo(1.0 / 3.0);
        }
    }

    @Nested
    @DisplayName("once it is full")
    class Rolling {

        @Test
        @DisplayName("the oldest block is dropped as a new one comes in")
        void dropsTheOldest() {
            MiningWindow window = new MiningWindow(3);
            window.record(true);   // will be dropped
            window.record(false);
            window.record(false);
            assertThat(window.oreCount()).isEqualTo(1);

            window.record(false);  // pushes the first "true" out
            assertThat(window.totalCount())
                    .as("the window does not grow past its size")
                    .isEqualTo(3);
            assertThat(window.oreCount())
                    .as("the ore block that fell out of the window no longer counts")
                    .isZero();
        }

        @Test
        @DisplayName("a long run of ore after a long run of stone reads as recent behaviour, not history")
        void reflectsRecentBehaviourOnly() {
            MiningWindow window = new MiningWindow(5);
            for (int i = 0; i < 20; i++) {
                window.record(false);
            }
            for (int i = 0; i < 5; i++) {
                window.record(true);
            }

            assertThat(window.oreCount()).isEqualTo(5);
            assertThat(window.ratio()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("a size of zero or less is still a window of at least one")
    void sizeIsAtLeastOne() {
        MiningWindow window = new MiningWindow(0);
        window.record(true);
        window.record(false);

        assertThat(window.totalCount())
                .as("a window that could hold nothing would divide by zero on the first ratio")
                .isEqualTo(1);
    }
}
