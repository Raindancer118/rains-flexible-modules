package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashCheck;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Form;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What a handful of cash is really worth, and what of it is forged — before anything is credited. */
class CashCheckRuleTest {

    private final CashCheckRule rule = new CashCheckRule();

    private static CashPiece coin(int count, String seal) {
        return new CashPiece(Money.of(1), count, Form.COIN, null, false, seal);
    }

    private static CashPiece cheque(long value, int count, String serial, String seal) {
        return new CashPiece(Money.of(value), count, Form.NOTE, serial, true, seal);
    }

    private CashCheck check(Map<Integer, CashPiece> slots, Map<String, Money> registry, long coinsOut) {
        return rule.check(slots, Money.of(1), piece -> "good".equals(piece.seal()),
                serial -> Optional.ofNullable(registry.get(serial)), value -> coinsOut);
    }

    @Test
    @DisplayName("sealed coins and a sealed, registered cheque are credited in full")
    void genuine() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, coin(30, "good"));
        slots.put(1, cheque(5000, 1, "A", "good"));
        CashCheck result = check(slots, Map.of("A", Money.of(5000)), 100);
        assertThat(result.total()).isEqualTo(Money.of(5030));
        assertThat(result.serials()).containsExactly("A");
        assertThat(result.coins()).containsEntry(Money.of(1), 30);
        assertThat(result.taken()).containsEntry(0, 30).containsEntry(1, 1);
        assertThat(result.confiscated()).isEmpty();
    }

    @Test
    @DisplayName("a coin without the server's seal is forged, whatever it claims to be worth")
    void unsealedCoins() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, coin(64, "forged"));
        slots.put(1, coin(5, null));
        CashCheck result = check(slots, Map.of(), 1000);
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.confiscated()).containsEntry(0, 64).containsEntry(1, 5);
    }

    @Test
    @DisplayName("a coin worth anything but one coin is forged, even sealed")
    void wrongValue() {
        CashCheck result = check(Map.of(0, new CashPiece(Money.of(1_000_000), 1, Form.COIN, null, false, "good")),
                Map.of(), 1000);
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.confiscated()).containsEntry(0, 1);
    }

    @Test
    @DisplayName("a stack of copies of one cheque is worth one cheque; the copies are confiscated")
    void stackedCopies() {
        CashCheck result = check(Map.of(4, cheque(5000, 2, "A", "good")), Map.of("A", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.of(5000));
        assertThat(result.taken()).containsEntry(4, 2);
        assertThat(result.confiscated()).containsEntry(4, 1);
    }

    @Test
    @DisplayName("the same serial twice, in two slots, is credited once")
    void twoSlots() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, cheque(5000, 1, "A", "good"));
        slots.put(1, cheque(5000, 1, "A", "good"));
        CashCheck result = check(slots, Map.of("A", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.of(5000));
        assertThat(result.confiscated()).containsEntry(1, 1);
    }

    @Test
    @DisplayName("a cheque whose value differs from what was issued, never issued, or unsealed, is forged")
    void forgedCheques() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, cheque(999_999, 1, "A", "good"));
        slots.put(1, cheque(5000, 1, "NEVER", "good"));
        slots.put(2, cheque(5000, 1, "B", "forged"));
        CashCheck result = check(slots, Map.of("A", Money.of(5000), "B", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.confiscated()).containsKeys(0, 1, 2);
    }

    @Test
    @DisplayName("more coins than are in circulation are not credited — and not confiscated, they may be somebody's real ones")
    void beyondTheFloat() {
        CashCheck result = check(Map.of(0, coin(10, "good")), Map.of(), 4);
        assertThat(result.total()).isEqualTo(Money.of(4));
        assertThat(result.taken()).containsEntry(0, 4);
        assertThat(result.confiscated()).isEmpty();
        assertThat(result.overTheFloat()).isEqualTo(6);
    }
}
