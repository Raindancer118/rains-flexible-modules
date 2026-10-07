package de.raindancer.modules.anticheat.model;

/**
 * One failed check.
 *
 * @param weight how much violation level it adds — 1 for an ordinary failure, more for one that
 *               cannot happen by accident
 * @param detail the numbers behind it, for staff ("dy 0.000, expected -0.078")
 */
public record Flag(CheckType check, double weight, String detail) {

    public Flag {
        if (check == null) {
            throw new IllegalArgumentException("a flag needs a check");
        }
        weight = Double.isFinite(weight) ? Math.max(0, weight) : 1;
        detail = detail == null ? "" : detail;
    }

    public static Flag of(CheckType check, String detail) {
        return new Flag(check, 1, detail);
    }

    public static Flag of(CheckType check, double weight, String detail) {
        return new Flag(check, weight, detail);
    }
}
