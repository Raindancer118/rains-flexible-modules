package de.raindancer.modules.economy.rules;

/**
 * Mines on a five-by-five field. After {@code k} safe tiles with {@code m} mines the stake is multiplied by
 * {@code (1 − edge) / P(k safe)}, so cashing out after any number of tiles returns exactly one minus the edge.
 */
public final class MinesRule implements IEconomyRule {

    public static final int TILES = 25;

    public double survival(int mines, int safePicks) {
        double chance = 1;
        for (int i = 0; i < safePicks; i++) {
            int safeLeft = TILES - mines - i;
            if (safeLeft <= 0) {
                return 0;
            }
            chance *= safeLeft / (double) (TILES - i);
        }
        return chance;
    }

    public double multiplier(int mines, int safePicks, double edge) {
        if (safePicks <= 0) {
            return 1;
        }
        double chance = survival(mines, safePicks);
        return chance <= 0 ? 0 : (1.0 - Math.max(0, Math.min(0.5, edge))) / chance;
    }

    public boolean validMines(int mines) {
        return mines >= 1 && mines <= TILES - 1;
    }

    @Override
    public String describe() {
        return "what a mines field pays after so many safe tiles";
    }
}
