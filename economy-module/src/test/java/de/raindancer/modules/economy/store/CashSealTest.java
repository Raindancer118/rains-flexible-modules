package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CashSealTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a seal fits exactly what it was made for, and survives a restart with the same key")
    void sealing() {
        CashSeal seal = CashSeal.load(folder.resolve("cash.key"));
        String coin = seal.seal(Money.of(1), Form.COIN, null);
        assertThat(seal.verify(new CashPiece(Money.of(1), 64, Form.COIN, null, false, coin))).isTrue();
        assertThat(seal.verify(new CashPiece(Money.of(2), 1, Form.COIN, null, false, coin))).as("another value").isFalse();
        assertThat(seal.verify(new CashPiece(Money.of(1), 1, Form.COIN, null, false, null))).isFalse();

        String cheque = seal.seal(Money.of(500), Form.NOTE, "ABCD");
        assertThat(seal.verify(new CashPiece(Money.of(500), 1, Form.NOTE, "ABCE", true, cheque))).as("another serial").isFalse();
        assertThat(CashSeal.load(folder.resolve("cash.key")).verify(new CashPiece(Money.of(500), 1, Form.NOTE, "ABCD",
                true, cheque))).as("same key after a restart").isTrue();
    }

    @Test
    @DisplayName("another server's key makes another server's money")
    void otherKey() {
        String foreign = CashSeal.load(folder.resolve("a.key")).seal(Money.of(1), Form.COIN, null);
        assertThat(CashSeal.load(folder.resolve("b.key")).verify(new CashPiece(Money.of(1), 1, Form.COIN, null, false,
                foreign))).isFalse();
    }
}
