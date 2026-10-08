package de.raindancer.modules.economy.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Scratch cards: nine fields, three alike win that symbol's prize. The prize table's raw return is scaled
 * so a ticket returns exactly one minus the house edge on average.
 */
public final class ScratchRule implements IEconomyRule {

    /** A prize: how often it comes up, and what it pays in ticket prices before the edge. */
    public enum Prize {
        CLOVER(0.20, 1), CHERRY(0.08, 2), BELL(0.04, 5), STAR(0.012, 20), CROWN(0.0025, 100), DIAMOND(0.0004, 1000);

        private final double chance;
        private final double times;

        Prize(double chance, double times) {
            this.chance = chance;
            this.times = times;
        }

        public double chance() {
            return chance;
        }

        public double times() {
            return times;
        }
    }

    private static final double RAW_RETURN = rawReturn();

    static double rawReturn() {
        double total = 0;
        for (Prize prize : Prize.values()) {
            total += prize.chance * prize.times;
        }
        return total;
    }

    /** @param uniform a number in [0, 1); null for a losing ticket */
    public Prize draw(double uniform) {
        double left = uniform;
        for (Prize prize : Prize.values()) {
            left -= prize.chance;
            if (left < 0) {
                return prize;
            }
        }
        return null;
    }

    /** What a prize pays per ticket price, with the edge built in. */
    public double multiplier(Prize prize, double edge) {
        return prize == null ? 0 : prize.times * (1.0 - Math.max(0, Math.min(0.5, edge))) / RAW_RETURN;
    }

    /**
     * The nine fields: the prize three times when there is one, and never three of anything else, so what
     * a player reads is exactly what they won.
     */
    public List<Prize> fields(Prize won, Random random) {
        List<Prize> fields = new ArrayList<>();
        if (won != null) {
            fields.add(won);
            fields.add(won);
            fields.add(won);
        }
        List<Prize> others = new ArrayList<>(List.of(Prize.values()));
        others.remove(won);
        int[] used = new int[Prize.values().length];
        while (fields.size() < 9) {
            Prize pick = others.get(random.nextInt(others.size()));
            if (used[pick.ordinal()] < 2) {
                used[pick.ordinal()]++;
                fields.add(pick);
            }
        }
        Collections.shuffle(fields, random);
        return fields;
    }

    @Override
    public String describe() {
        return "what a scratch card wins, and the fields that show it";
    }
}
