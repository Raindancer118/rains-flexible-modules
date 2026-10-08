package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashCheck;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Form;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Which of the cash being paid in is real, before a cent is credited.
 *
 * <p>Nothing written on an item is trusted on its own: a creative client can send any item it likes. A
 * note is checked against the serial register (its value included) and is worth one, however many copies
 * are stacked on it. A coin must be one this server issues — that value in that material — and no more
 * coins of a value can be paid in than are in circulation. Forged items are confiscated; coins over the
 * circulation are only refused, because they may be an honest player's while a forger got there first.
 */
public final class CashCheckRule implements IEconomyRule {

    /**
     * @param slots       inventory slot to what the stack there says it is
     * @param materials   inventory slot to the material actually there
     * @param registry    a serial's issued value, if it is outstanding
     * @param circulation how many coins of a value are out
     */
    public CashCheck check(Map<Integer, CashPiece> slots, Map<Integer, Material> materials, List<Denomination> issued,
                           Function<String, Optional<Money>> registry, ToLongFunction<Money> circulation) {
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        Map<Integer, Integer> confiscated = new LinkedHashMap<>();
        Map<Money, Integer> coins = new LinkedHashMap<>();
        Map<Money, Long> left = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<String> serials = new ArrayList<>();
        long total = 0;
        int overTheFloat = 0;

        for (Map.Entry<Integer, CashPiece> each : slots.entrySet()) {
            int slot = each.getKey();
            CashPiece piece = each.getValue();
            int count = Math.max(0, piece.count());
            if (count == 0) {
                continue;
            }
            if (piece.numbered()) {
                Optional<Money> registered = registry.apply(piece.serial());
                boolean genuine = registered.isPresent() && registered.get().equals(piece.each())
                        && seen.add(piece.serial());
                if (!genuine) {
                    taken.put(slot, count);
                    confiscated.put(slot, count);
                    continue;
                }
                serials.add(piece.serial());
                total = Math.addExact(total, piece.each().minor());
                taken.put(slot, count);
                if (count > 1) {
                    confiscated.put(slot, count - 1);
                }
                continue;
            }
            boolean issuedHere = piece.form() == Form.COIN && issued.stream().anyMatch(denomination ->
                    denomination.form() == Form.COIN && denomination.value().equals(piece.each())
                            && denomination.material() == materials.get(slot));
            if (!issuedHere) {
                taken.put(slot, count);
                confiscated.put(slot, count);
                continue;
            }
            long out = left.computeIfAbsent(piece.each(), circulation::applyAsLong);
            int accepted = (int) Math.max(0, Math.min(count, out));
            overTheFloat += count - accepted;
            if (accepted == 0) {
                continue;
            }
            left.put(piece.each(), out - accepted);
            coins.merge(piece.each(), accepted, Integer::sum);
            total = Math.addExact(total, Math.multiplyExact(piece.each().minor(), accepted));
            taken.put(slot, accepted);
        }
        return new CashCheck(Money.of(total), serials, coins, taken, confiscated, overTheFloat);
    }

    @Override
    public String describe() {
        return "which of the cash being paid in is real, and what it is worth";
    }
}
