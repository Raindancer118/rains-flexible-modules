package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDigestTest {

    private long now = 1_000_000;
    private final AlertDigest digest = new AlertDigest(() -> now);
    private final UUID steve = UUID.randomUUID();

    @Test
    @DisplayName("the first flag is told at once; what follows within the window becomes one line")
    void oneLinePerWindow() {
        assertThat(digest.offer(steve, "Steve", CheckType.FLY, 4, "rising", 40)).isPresent();
        for (int i = 0; i < 20; i++) {
            now += 100;
            assertThat(digest.offer(steve, "Steve", i % 2 == 0 ? CheckType.FLY : CheckType.SPEED, 5 + i * 0.1, "x", 48)).isEmpty();
        }
        assertThat(digest.due()).as("not yet").isEmpty();
        now += AlertDigest.WINDOW_MILLIS;
        List<AlertDigest.Line> lines = digest.due();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().counts()).containsEntry(CheckType.FLY, 10).containsEntry(CheckType.SPEED, 10);
        assertThat(lines.getFirst().level()).isGreaterThan(6.8);
        assertThat(lines.getFirst().ping()).isEqualTo(48);
        assertThat(digest.due()).as("told once").isEmpty();
    }

    @Test
    @DisplayName("after a quiet window, the next flag is told at once again")
    void quietAgain() {
        digest.offer(steve, "Steve", CheckType.FLY, 4, "rising", 40);
        now += AlertDigest.WINDOW_MILLIS * 2;
        digest.due();
        assertThat(digest.offer(steve, "Steve", CheckType.FLY, 4, "rising", 40)).isPresent();
    }

    @Test
    @DisplayName("a check that alerts on its first flag waits for a second before reaching chat")
    void firstFlagChecksWait() {
        assertThat(CheckType.NUKER.alertAt()).isLessThanOrEqualTo(1);
        assertThat(digest.offer(steve, "Steve", CheckType.NUKER, 1, "x", 40)).isEmpty();
        now += AlertDigest.WINDOW_MILLIS;
        assertThat(digest.due()).as("one flag alone stays out of chat").isEmpty();
        assertThat(digest.offer(steve, "Steve", CheckType.NUKER, 2, "x", 40)).as("a second one within half a minute is").isPresent();
    }

    @Test
    @DisplayName("each player has their own window")
    void perPlayer() {
        UUID alex = UUID.randomUUID();
        assertThat(digest.offer(steve, "Steve", CheckType.FLY, 4, "x", 40)).isPresent();
        assertThat(digest.offer(alex, "Alex", CheckType.FLY, 4, "x", 40)).isPresent();
    }

    @Test
    @DisplayName("what no vanilla client can send is told at once, even on its first flag")
    void impossibleAtOnce() {
        assertThat(CheckType.BAD_PACKETS.impossible()).isTrue();
        assertThat(digest.offer(steve, "Steve", CheckType.BAD_PACKETS, 1, "pitch 120", 40)).isPresent();
        assertThat(digest.offer(UUID.randomUUID(), "Alex", CheckType.MULTI_AURA, 1, "two at once", 40)).isPresent();
    }
}
