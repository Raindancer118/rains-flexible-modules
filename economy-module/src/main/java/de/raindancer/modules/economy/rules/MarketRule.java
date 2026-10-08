package de.raindancer.modules.economy.rules;

/**
 * Supply and demand as one number per item: pressure. Buying pushes it up, selling down, and it wears off
 * with a half-life. The price multiplier follows it through {@code tanh}, so it moves quickly at first,
 * then ever more slowly, and never past the swing.
 */
public final class MarketRule implements IEconomyRule {

    public double pushed(double pressure, int quantity, int stackSize, double perStack, boolean buying) {
        double stacks = quantity / (double) Math.max(1, stackSize);
        double push = stacks * Math.max(0, perStack);
        return buying ? pressure + push : pressure - push;
    }

    public double decayed(double pressure, long elapsedMillis, double halfLifeHours) {
        if (elapsedMillis <= 0 || halfLifeHours <= 0) {
            return pressure;
        }
        double halfLives = elapsedMillis / (halfLifeHours * 3_600_000.0);
        return pressure * Math.pow(0.5, halfLives);
    }

    public double multiplier(double pressure, double swing) {
        return 1.0 + Math.max(0, Math.min(0.95, swing)) * Math.tanh(pressure);
    }

    @Override
    public String describe() {
        return "how buying and selling move an item's price, and how that wears off";
    }
}
