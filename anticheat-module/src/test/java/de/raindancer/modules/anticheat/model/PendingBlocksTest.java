package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingBlocksTest {

    @Test
    @DisplayName("a block change stays uncertain until the ping behind it comes back, plus a tick of grace")
    void untilConfirmed() {
        PendingBlocks blocks = new PendingBlocks();
        blocks.sent(10, 63, -5, 7, 1000);
        assertThat(blocks.uncertain(9, 62, -6, 11, 64, -4, 1500, PendingBlocks.GIVE_UP_MILLIS)).isTrue();
        blocks.confirmed(7, 1600);
        assertThat(blocks.uncertain(9, 62, -6, 11, 64, -4, 1620, PendingBlocks.GIVE_UP_MILLIS)).isTrue();
        assertThat(blocks.uncertain(9, 62, -6, 11, 64, -4, 1700, PendingBlocks.GIVE_UP_MILLIS)).isFalse();
    }

    @Test
    @DisplayName("only changes inside the asked box count")
    void outsideBox() {
        PendingBlocks blocks = new PendingBlocks();
        blocks.sent(10, 63, -5, 7, 1000);
        assertThat(blocks.uncertain(11, 62, -6, 12, 64, -4, 1100, PendingBlocks.GIVE_UP_MILLIS)).isFalse();
        assertThat(blocks.uncertain(9, 64, -6, 11, 65, -4, 1100, PendingBlocks.GIVE_UP_MILLIS)).isFalse();
    }

    @Test
    @DisplayName("a later answer also confirms earlier pings; a lost one expires on its own")
    void orderedAndExpiring() {
        PendingBlocks blocks = new PendingBlocks();
        blocks.sent(1, 1, 1, 6, 1000);
        blocks.sent(2, 1, 1, 5, 1000);
        blocks.confirmed(5, 1200);
        assertThat(blocks.uncertain(1, 1, 1, 2, 1, 1, 1400, PendingBlocks.GIVE_UP_MILLIS)).isFalse();

        blocks.sent(3, 1, 1, 4, 2000);
        assertThat(blocks.uncertain(3, 1, 1, 3, 1, 1, 2000 + PendingBlocks.GIVE_UP_MILLIS - 1, PendingBlocks.GIVE_UP_MILLIS)).isTrue();
        assertThat(blocks.uncertain(3, 1, 1, 3, 1, 1, 2000 + PendingBlocks.GIVE_UP_MILLIS + 1, PendingBlocks.GIVE_UP_MILLIS)).isFalse();
    }

    @Test
    @DisplayName("our ids count down, so 'later' means a smaller id")
    void countingDown() {
        PendingBlocks blocks = new PendingBlocks();
        blocks.sent(1, 1, 1, -100, 1000);
        blocks.confirmed(-101, 1100);
        assertThat(blocks.uncertain(1, 1, 1, 1, 1, 1, 1300, PendingBlocks.GIVE_UP_MILLIS)).isFalse();
    }

    @Test
    @DisplayName("terraforming thousands of blocks stays bounded")
    void bounded() {
        PendingBlocks blocks = new PendingBlocks();
        for (int i = 0; i < 100_000; i++) {
            blocks.sent(i, 0, 0, -i, 1000);
        }
        assertThat(blocks.size()).isLessThanOrEqualTo(PendingBlocks.CAPACITY);
    }

    @Test
    @DisplayName("an unanswered change only counts for as long as the client's ping could explain")
    void patienceFollowsPing() {
        PendingBlocks blocks = new PendingBlocks();
        blocks.sent(1, 1, 1, -1, 1000);
        assertThat(blocks.uncertain(1, 1, 1, 1, 1, 1, 1300, 400)).isTrue();
        assertThat(blocks.uncertain(1, 1, 1, 1, 1, 1, 1500, 400)).isFalse();
    }
}
