package de.raindancer.modules.moderation.model;

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
        assertThat(digest.offer(steve, "Steve", CheckType.GRIM_MOVEMENT, 4, "rising", 40)).isPresent();
        for (int i = 0; i < 20; i++) {
            now += 100;
            assertThat(digest.offer(steve, "Steve", i % 2 == 0 ? CheckType.GRIM_MOVEMENT : CheckType.GRIM_INVENTORY, 5 + i * 0.1, "x", 48)).isEmpty();
        }
        assertThat(digest.due()).as("not yet").isEmpty();
        now += AlertDigest.WINDOW_MILLIS;
        List<AlertDigest.Line> lines = digest.due();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().counts()).containsEntry(CheckType.GRIM_MOVEMENT, 10).containsEntry(CheckType.GRIM_INVENTORY, 10);
        assertThat(lines.getFirst().level()).isGreaterThan(6.8);
        assertThat(lines.getFirst().ping()).isEqualTo(48);
        assertThat(digest.due()).as("told once").isEmpty();
    }

    @Test
    @DisplayName("after a quiet window, the next flag is told at once again")
    void quietAgain() {
        digest.offer(steve, "Steve", CheckType.GRIM_MOVEMENT, 4, "rising", 40);
        now += AlertDigest.WINDOW_MILLIS * 2;
        digest.due();
        assertThat(digest.offer(steve, "Steve", CheckType.GRIM_MOVEMENT, 4, "rising", 40)).isPresent();
    }

    @Test
    @DisplayName("each player has their own window")
    void perPlayer() {
        UUID alex = UUID.randomUUID();
        assertThat(digest.offer(steve, "Steve", CheckType.GRIM_MOVEMENT, 4, "x", 40)).isPresent();
        assertThat(digest.offer(alex, "Alex", CheckType.GRIM_MOVEMENT, 4, "x", 40)).isPresent();
    }

    @Test
    @DisplayName("what no vanilla client can send is told at once, even on its first flag")
    void impossibleAtOnce() {
        assertThat(CheckType.CLIENT.impossible()).isTrue();
        assertThat(digest.offer(steve, "Steve", CheckType.CLIENT, 1, "client brand 'wurst'", 40)).isPresent();
    }
}
