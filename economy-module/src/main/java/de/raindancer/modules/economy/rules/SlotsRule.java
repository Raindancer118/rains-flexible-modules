package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.SlotSymbol;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The slot machine's paytable, and the exact return it gives.
 *
 * <p>The raw table pays three of a kind by symbol, any two netherite 25×, one netherite 1.5×, and two
 * alike side by side the stake back. Its return is worked out by going through every combination, and
 * every payout is scaled by {@code (1 − edge) / that return} — so the machine returns exactly what the
 * owner set, however the table is tuned.
 */
public final class SlotsRule implements IEconomyRule {

    private static final double BASE_RETURN = baseReturn();

    /** @param random a whole number from 0 (inclusive) to the bound given (exclusive) */
    public List<SlotSymbol> spin(IntUnaryOperator random) {
        return List.of(pick(random), pick(random), pick(random));
    }

    private static SlotSymbol pick(IntUnaryOperator random) {
        int roll = random.applyAsInt(SlotSymbol.totalWeight());
        for (SlotSymbol symbol : SlotSymbol.values()) {
            roll -= symbol.weight();
            if (roll < 0) {
                return symbol;
            }
        }
        return SlotSymbol.COAL;
    }

    /** What the raw table pays, as a multiple of the stake. */
    public static double rawMultiplier(List<SlotSymbol> reels) {
        SlotSymbol a = reels.get(0);
        SlotSymbol b = reels.get(1);
        SlotSymbol c = reels.get(2);
        if (a == b && b == c) {
            return a.threeOfAKind();
        }
        long netherite = reels.stream().filter(symbol -> symbol == SlotSymbol.NETHERITE).count();
        if (netherite == 2) {
            return 25;
        }
        if (netherite == 1) {
            return 1.5;
        }
        if (a == b || b == c) {
            return 1;
        }
        return 0;
    }

    /** The raw table's expected return per unit staked, over every combination. */
    public static double baseReturn() {
        double total = Math.pow(SlotSymbol.totalWeight(), 3);
        double expected = 0;
        for (SlotSymbol a : SlotSymbol.values()) {
            for (SlotSymbol b : SlotSymbol.values()) {
                for (SlotSymbol c : SlotSymbol.values()) {
                    double chance = a.weight() * b.weight() * c.weight() / total;
                    expected += chance * rawMultiplier(List.of(a, b, c));
                }
            }
        }
        return expected;
    }

    /** The multiplier actually paid, with the house edge built in. */
    public double multiplier(List<SlotSymbol> reels, double edge) {
        return rawMultiplier(reels) * (1.0 - Math.max(0, Math.min(0.5, edge))) / BASE_RETURN;
    }

    public Money payout(Money stake, List<SlotSymbol> reels, double edge) {
        return stake.share(multiplier(reels, edge));
    }

    @Override
    public String describe() {
        return "what a spin of the slot machine pays, scaled so it returns exactly one minus the house edge";
    }
}
