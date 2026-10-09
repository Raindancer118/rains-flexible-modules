package de.raindancer.modules.claims;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.model.EntryFeeCut;
import de.raindancer.modules.claims.model.UpkeepBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpkeepBillTest {

    @Test
    @DisplayName("without growth every chunk costs the same")
    void flat() {
        assertThat(UpkeepBill.of(Money.of(1000), 0, 5)).isEqualTo(Money.of(5000));
    }

    @Test
    @DisplayName("nothing held, or nothing charged per chunk, is no bill")
    void nothing() {
        assertThat(UpkeepBill.of(Money.of(1000), 10, 0)).isEqualTo(Money.ZERO);
        assertThat(UpkeepBill.of(Money.ZERO, 10, 7)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("each further chunk costs growth-percent more than the one before it")
    void progressive() {
        // 100 + 110 + 121 = 331
        assertThat(UpkeepBill.of(Money.of(100), 10, 3)).isEqualTo(Money.of(331));
        assertThat(UpkeepBill.chunkCost(Money.of(100), 10, 3)).isEqualTo(Money.of(121));
    }

    @Test
    @DisplayName("a big landholder pays more per chunk than a small one")
    void bigPaysMorePerChunk() {
        long small = UpkeepBill.of(Money.of(1000), 5, 2).minor() / 2;
        long big = UpkeepBill.of(Money.of(1000), 5, 40).minor() / 40;
        assertThat(big).isGreaterThan(small);
    }

    @Test
    @DisplayName("an absurd holding saturates instead of overflowing into a refund")
    void saturates() {
        Money huge = UpkeepBill.of(Money.of(1_000_000), 100, 100_000);
        assertThat(huge.minor()).isPositive();
    }

    @Test
    @DisplayName("negative growth is read as none")
    void negativeGrowth() {
        assertThat(UpkeepBill.of(Money.of(100), -50, 3)).isEqualTo(Money.of(300));
    }

    @Test
    @DisplayName("the server cut destroys that share of a fee, rounded down, and never more than all of it")
    void entryFeeCut() {
        assertThat(EntryFeeCut.destroyed(10, 0)).isZero();
        assertThat(EntryFeeCut.destroyed(10, 30)).isEqualTo(3);
        assertThat(EntryFeeCut.destroyed(7, 50)).isEqualTo(3);
        assertThat(EntryFeeCut.destroyed(10, 100)).isEqualTo(10);
        assertThat(EntryFeeCut.destroyed(10, 250)).isEqualTo(10);
        assertThat(EntryFeeCut.destroyed(10, -5)).isZero();
        assertThat(EntryFeeCut.kept(10, 30)).isEqualTo(7);
    }
}
