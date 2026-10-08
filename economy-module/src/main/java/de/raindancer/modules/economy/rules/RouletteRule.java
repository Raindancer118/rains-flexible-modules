package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.RouletteBet;

import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * European roulette: 37 pockets, a single green zero. A bet covering {@code c} pockets wins with chance
 * {@code c/37} and pays {@code stake × (1 − edge) × 37 / c}, so every bet returns exactly one minus the
 * house edge — the same promise every other game here keeps.
 */
public final class RouletteRule implements IEconomyRule {

    public enum Colour { RED, BLACK, GREEN }

    /** The pockets in the order they sit around a European wheel. */
    public static final List<Integer> WHEEL = List.of(0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8,
            23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26);

    private static final Set<Integer> RED = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    public Colour colourOf(int pocket) {
        if (pocket == 0) {
            return Colour.GREEN;
        }
        return RED.contains(pocket) ? Colour.RED : Colour.BLACK;
    }

    public boolean wins(RouletteBet bet, int pocket) {
        boolean zero = pocket == 0;
        return switch (bet.kind()) {
            case RED -> colourOf(pocket) == Colour.RED;
            case BLACK -> colourOf(pocket) == Colour.BLACK;
            case GREEN -> zero;
            case EVEN -> !zero && pocket % 2 == 0;
            case ODD -> pocket % 2 == 1;
            case LOW -> pocket >= 1 && pocket <= 18;
            case HIGH -> pocket >= 19;
            case FIRST_DOZEN -> pocket >= 1 && pocket <= 12;
            case SECOND_DOZEN -> pocket >= 13 && pocket <= 24;
            case THIRD_DOZEN -> pocket >= 25;
            case NUMBER -> pocket == bet.number();
        };
    }

    public int covered(RouletteBet bet) {
        int count = 0;
        for (int pocket = 0; pocket <= 36; pocket++) {
            if (wins(bet, pocket)) {
                count++;
            }
        }
        return count;
    }

    /** What a winning bet returns in total, stake included. */
    public Money payout(Money stake, RouletteBet bet, double edge) {
        return stake.share((1.0 - Math.max(0, Math.min(0.5, edge))) * 37.0 / covered(bet));
    }

    /** @param random a whole number from 0 (inclusive) to the bound given (exclusive) */
    public int spin(IntUnaryOperator random) {
        return Math.max(0, Math.min(36, random.applyAsInt(37)));
    }

    @Override
    public String describe() {
        return "which roulette bets win on a pocket, and what they pay";
    }
}
