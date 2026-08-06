package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.Schedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure timetable behind scheduled round events (supply drops, beacon spawns): which entries are due
 * at the current game time and have not fired yet, including the case where the server was down when
 * some of them fell due.
 */
class ScheduleTest {

    private static final List<Duration> TIMETABLE = List.of(
            Duration.ofMinutes(20), Duration.ofMinutes(45), Duration.ofMinutes(75));

    @Test
    @DisplayName("Nothing is due before the first point on the timetable")
    void nothingDueBeforeFirstPoint() {
        assertEquals(List.of(),
                Schedule.dueIndices(TIMETABLE, Set.of(), Duration.ofMinutes(19)));
    }

    @Test
    @DisplayName("The first point becomes due exactly when its time arrives")
    void firstPointDue() {
        assertEquals(List.of(0),
                Schedule.dueIndices(TIMETABLE, Set.of(), Duration.ofMinutes(20)));
    }

    @Test
    @DisplayName("Already-fired entries are skipped")
    void firedEntriesSkipped() {
        assertEquals(List.of(1),
                Schedule.dueIndices(TIMETABLE, Set.of(0), Duration.ofMinutes(50)));
    }

    @Test
    @DisplayName("A missed window after downtime is caught up on the next look, not lost")
    void missedEntriesCaughtUpAfterDowntime() {
        // The server was offline while game time kept advancing (a COUNT policy): both are due at once.
        assertEquals(List.of(1, 2),
                Schedule.dueIndices(TIMETABLE, Set.of(0), Duration.ofMinutes(80)));
    }

    @Test
    @DisplayName("Everything already fired means nothing fires again after a restart")
    void nothingFiresTwiceAfterRestart() {
        assertEquals(List.of(),
                Schedule.dueIndices(TIMETABLE, Set.of(0, 1, 2), Duration.ofHours(3)));
    }
}
