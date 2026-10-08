package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashCheck;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Form;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What a handful of cash is really worth, and what of it is forged — before anything is credited. */
class CashCheckRuleTest {

    private final CashCheckRule rule = new CashCheckRule();
    private final Denomination ten = new Denomination(Money.of(1000), Material.GOLD_INGOT, Form.COIN);
    private final List<Denomination> issued = List.of(ten);

    private static CashPiece coin(long each, int count) {
        return new CashPiece(Money.of(each), count, Form.COIN, null, false);
    }

    private static CashPiece note(long each, int count, String serial) {
        return new CashPiece(Money.of(each), count, Form.NOTE, serial, false);
    }

    private CashCheck check(Map<Integer, CashPiece> slots, Map<Integer, Material> materials, Map<String, Money> registry,
                            long coinsOut) {
        return rule.check(slots, materials, issued, serial -> Optional.ofNullable(registry.get(serial)),
                value -> coinsOut);
    }

    @Test
    @DisplayName("genuine coins and a genuine note are credited in full")
    void genuine() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, coin(1000, 3));
        slots.put(1, note(5000, 1, "A"));
        CashCheck result = check(slots, Map.of(0, Material.GOLD_INGOT, 1, Material.PAPER), Map.of("A", Money.of(5000)), 10);
        assertThat(result.total()).isEqualTo(Money.of(8000));
        assertThat(result.serials()).containsExactly("A");
        assertThat(result.coins()).containsEntry(Money.of(1000), 3);
        assertThat(result.taken()).containsEntry(0, 3).containsEntry(1, 1);
        assertThat(result.confiscated()).isEmpty();
    }

    @Test
    @DisplayName("a stack of copies of one note is worth one note; the copies are confiscated")
    void stackedCopies() {
        CashCheck result = check(Map.of(4, note(5000, 2, "A")), Map.of(4, Material.PAPER),
                Map.of("A", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.of(5000));
        assertThat(result.taken()).containsEntry(4, 2);
        assertThat(result.confiscated()).containsEntry(4, 1);
    }

    @Test
    @DisplayName("the same serial twice, in two slots, is credited once")
    void twoSlots() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, note(5000, 1, "A"));
        slots.put(1, note(5000, 1, "A"));
        CashCheck result = check(slots, Map.of(0, Material.PAPER, 1, Material.PAPER), Map.of("A", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.of(5000));
        assertThat(result.confiscated()).containsEntry(1, 1);
    }

    @Test
    @DisplayName("a note whose value differs from what was issued, or that was never issued, is forged")
    void forgedNotes() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, note(999_999, 1, "A"));
        slots.put(1, note(5000, 1, "NEVER"));
        CashCheck result = check(slots, Map.of(0, Material.PAPER, 1, Material.PAPER), Map.of("A", Money.of(5000)), 0);
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.confiscated()).containsEntry(0, 1).containsEntry(1, 1);
    }

    @Test
    @DisplayName("a coin of a value, or a material, this server never issued is forged")
    void forgedCoins() {
        Map<Integer, CashPiece> slots = new LinkedHashMap<>();
        slots.put(0, coin(100_000_000_000L, 64));
        slots.put(1, coin(1000, 5));
        CashCheck result = check(slots, Map.of(0, Material.GOLD_INGOT, 1, Material.DIAMOND), Map.of(), 1000);
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.confiscated()).containsEntry(0, 64).containsEntry(1, 5);
    }

    @Test
    @DisplayName("more coins than are in circulation are not credited — and not confiscated, they may be somebody's real ones")
    void beyondTheFloat() {
        CashCheck result = check(Map.of(0, coin(1000, 10)), Map.of(0, Material.GOLD_INGOT), Map.of(), 4);
        assertThat(result.total()).isEqualTo(Money.of(4000));
        assertThat(result.taken()).containsEntry(0, 4);
        assertThat(result.confiscated()).isEmpty();
        assertThat(result.overTheFloat()).isEqualTo(6);
    }
}
