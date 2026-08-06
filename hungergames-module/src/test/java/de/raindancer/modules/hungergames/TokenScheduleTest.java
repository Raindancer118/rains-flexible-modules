package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.TokenSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenScheduleTest {

    private static final Duration FIRST = Duration.ofMinutes(10);
    private static final Duration INTERVAL = Duration.ofMinutes(10);

    @Test
    void noWaveBeforeTheFirstPoint() {
        assertEquals(0, TokenSchedule.dueWaves(Duration.ofMinutes(9), FIRST, INTERVAL));
    }

    @Test
    void firstWaveAtTheStartingPoint() {
        assertEquals(1, TokenSchedule.dueWaves(Duration.ofMinutes(10), FIRST, INTERVAL));
    }

    @Test
    void furtherWavesOnTheInterval() {
        assertEquals(1, TokenSchedule.dueWaves(Duration.ofMinutes(19), FIRST, INTERVAL));
        assertEquals(2, TokenSchedule.dueWaves(Duration.ofMinutes(20), FIRST, INTERVAL));
        assertEquals(4, TokenSchedule.dueWaves(Duration.ofMinutes(45), FIRST, INTERVAL));
    }

    @Test
    void rejoinCatchesUpMissingWaves() {
        int due = TokenSchedule.dueWaves(Duration.ofMinutes(45), FIRST, INTERVAL); // 4
        assertEquals(3, TokenSchedule.pendingTokens(due, 1, 1, 1, 0));
    }

    @Test
    void noDoubleTokensAfterARestart() {
        int due = TokenSchedule.dueWaves(Duration.ofMinutes(45), FIRST, INTERVAL);
        assertEquals(0, TokenSchedule.pendingTokens(due, due, 1, due, 0));
    }

    @Test
    void maximumPerPlayerCapsTheAmount() {
        assertEquals(2, TokenSchedule.pendingTokens(10, 0, 1, 3, 5));
        assertEquals(0, TokenSchedule.pendingTokens(10, 0, 1, 5, 5));
        assertEquals(10, TokenSchedule.pendingTokens(10, 0, 1, 3, 0)); // 0 = unlimited
    }

    @Test
    void severalTokensPerWave() {
        assertEquals(6, TokenSchedule.pendingTokens(3, 0, 2, 0, 0));
    }
}
