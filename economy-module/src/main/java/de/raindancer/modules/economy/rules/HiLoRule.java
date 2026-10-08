package de.raindancer.modules.economy.rules;

/**
 * Higher or lower than the card showing, from what is actually left in the shoe. A card of the same rank
 * loses either guess. Each right guess multiplies the stake by {@code (1 − edge) / p}, so every guess —
 * and so every run of them — returns exactly one minus the house edge.
 */
public final class HiLoRule implements IEconomyRule {

    /** The chance the next card is strictly higher (or lower) than {@code rank}. */
    public double chance(int rank, boolean higher, int[] ranksLeft) {
        int total = 0;
        int winning = 0;
        for (int r = 1; r <= 13; r++) {
            total += ranksLeft[r];
            if (higher ? r > rank : r < rank) {
                winning += ranksLeft[r];
            }
        }
        return total == 0 ? 0 : winning / (double) total;
    }

    /** What one right guess multiplies by; zero for a guess that cannot win. */
    public double step(double chance, double edge) {
        return chance <= 0 ? 0 : (1.0 - Math.max(0, Math.min(0.5, edge))) / chance;
    }

    public boolean wins(int shown, int next, boolean higher) {
        return higher ? next > shown : next < shown;
    }

    @Override
    public String describe() {
        return "the odds of the next card being higher or lower, and what a right guess pays";
    }
}
