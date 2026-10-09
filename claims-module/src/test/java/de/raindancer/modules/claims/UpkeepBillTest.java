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

    @Test
    @DisplayName("a claim's own fee is the flat fee when the area percent is zero")
    void perClaimFlat() {
        assertThat(UpkeepBill.claimFee(Money.of(500), 0, 9)).isEqualTo(Money.of(500));
        assertThat(UpkeepBill.claimFee(Money.ZERO, 50, 9)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a claim's own fee grows with its own size: fee * (1 + percent/100)^(chunks - 1)")
    void perClaimGrowsWithItsSize() {
        assertThat(UpkeepBill.claimFee(Money.of(1000), 10, 1)).isEqualTo(Money.of(1000));
        assertThat(UpkeepBill.claimFee(Money.of(1000), 10, 3)).isEqualTo(Money.of(1210));
    }

    @Test
    @DisplayName("one big claim costs more than two small ones of the same total")
    void bigClaimCostsMore() {
        Money big = UpkeepBill.claimFees(Money.of(1000), 50, java.util.List.of(8));
        Money small = UpkeepBill.claimFees(Money.of(1000), 50, java.util.List.of(4, 4));
        assertThat(big.minor()).isGreaterThan(small.minor());
    }

    @Test
    @DisplayName("a claim fee never overflows into arrears arithmetic")
    void claimFeeIsClamped() {
        assertThat(UpkeepBill.claimFee(Money.of(1000), 1000, 100_000).minor()).isLessThanOrEqualTo(1_000_000_000_000_000L);
    }

    @Test
    @DisplayName("the operators' percent scales the bill and is clamped to 0..100")
    void discount() {
        assertThat(UpkeepBill.discounted(Money.of(1000), 100)).isEqualTo(Money.of(1000));
        assertThat(UpkeepBill.discounted(Money.of(1000), 25)).isEqualTo(Money.of(250));
        assertThat(UpkeepBill.discounted(Money.of(1000), 0)).isEqualTo(Money.ZERO);
        assertThat(UpkeepBill.discounted(Money.of(1000), 250)).isEqualTo(Money.of(1000));
        assertThat(UpkeepBill.discounted(Money.of(1000), -5)).isEqualTo(Money.ZERO);
    }
}
