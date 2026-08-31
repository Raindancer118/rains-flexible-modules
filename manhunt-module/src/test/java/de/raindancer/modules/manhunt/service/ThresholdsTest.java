package de.raindancer.modules.manhunt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Crossing a mark, once and once only. Pure arithmetic — see the class itself. */
class ThresholdsTest {

    private static final double[] MARKS = {300, 60, 10};

    @Nested
    @DisplayName("crossing downwards")
    class Falling {

        @Test
        @DisplayName("nothing is announced while the value stays above every mark")
        void aboveEverything() {
            assertThat(new Thresholds(MARKS).crossed(600, 400)).isEmpty();
        }

        @Test
        @DisplayName("the mark just passed is the one announced")
        void announcesTheMarkPassed() {
            assertThat(new Thresholds(MARKS).crossed(310, 299)).contains(300.0);
        }

        @Test
        @DisplayName("landing exactly on a mark counts as passing it")
        void exactlyOnTheMark() {
            assertThat(new Thresholds(MARKS).crossed(310, 300)).contains(300.0);
        }

        @Test
        @DisplayName("a mark is announced once, however many times it is asked about")
        void onceOnly() {
            Thresholds thresholds = new Thresholds(MARKS);

            assertThat(thresholds.crossed(310, 299)).contains(300.0);
            assertThat(thresholds.crossed(299, 298)).isEmpty();
        }

        @Test
        @DisplayName("each mark gets its own turn as the value keeps falling")
        void eachInTurn() {
            Thresholds thresholds = new Thresholds(MARKS);

            assertThat(thresholds.crossed(310, 299)).contains(300.0);
            assertThat(thresholds.crossed(299, 59)).contains(60.0);
            assertThat(thresholds.crossed(59, 9)).contains(10.0);
            assertThat(thresholds.crossed(9, 1)).isEmpty();
        }

        @Test
        @DisplayName("a jump past several marks announces the lowest one actually reached")
        void aJumpAnnouncesTheLowest() {
            Thresholds thresholds = new Thresholds(MARKS);

            assertThat(thresholds.crossed(600, 5)).contains(10.0);
            // and the ones it flew past are spent, not queued up to fire later
            assertThat(thresholds.crossed(5, 4)).isEmpty();
        }
    }

    @Test
    @DisplayName("a value going back up announces nothing, and does not re-arm a spent mark")
    void risingIsSilent() {
        Thresholds thresholds = new Thresholds(MARKS);
        thresholds.crossed(310, 299);

        assertThat(thresholds.crossed(299, 400)).isEmpty();
        assertThat(thresholds.crossed(400, 299)).isEmpty();
    }

    @Test
    @DisplayName("a fresh hunt re-arms every mark")
    void resetting() {
        Thresholds thresholds = new Thresholds(MARKS);
        thresholds.crossed(310, 299);
        thresholds.reset();

        assertThat(thresholds.crossed(310, 299)).contains(300.0);
    }

    @Test
    @DisplayName("marks are ordered highest-first however they were handed in")
    void orderIndependent() {
        Thresholds thresholds = new Thresholds(new double[] {10, 300, 60});

        assertThat(thresholds.crossed(310, 299)).contains(300.0);
    }
}
