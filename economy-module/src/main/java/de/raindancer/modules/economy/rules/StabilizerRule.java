package de.raindancer.modules.economy.rules;

import java.util.Map;
import java.util.OptionalDouble;

/** One day's turn of the taps against inflation. */
public final class StabilizerRule implements IEconomyRule {

    /** The percent change on payouts and on fees, as the stabiliser has them set. */
    public record Taps(int faucet, int sink) {
        public static final Taps NEUTRAL = new Taps(0, 0);
    }

    /** Prices too high: payouts down, fees up, one step. Too low: the other way. Within the tolerance: as they are. */
    public Taps next(Taps now, double weeklyPercent, double targetPercent, double tolerancePercent, int step,
                     int most) {
        double error = weeklyPercent - targetPercent;
        if (Math.abs(error) <= Math.abs(tolerancePercent)) {
            return now;
        }
        int move = error > 0 ? -Math.abs(step) : Math.abs(step);
        int limit = Math.abs(most);
        return new Taps(Math.clamp(now.faucet() + move, -limit, limit), Math.clamp(now.sink() - move, -limit, limit));
    }

    /**
     * How much the basket's price moved over the last week, in percent; fewer days of history are stretched to a
     * week. Empty with fewer than two days to compare.
     *
     * @param basketByDay the basket's price by epoch day
     */
    public OptionalDouble weeklyPercent(Map<Long, Long> basketByDay, long today) {
        Long now = basketByDay.get(today);
        if (now == null || now <= 0) {
            return OptionalDouble.empty();
        }
        long baseDay = Long.MIN_VALUE;
        for (Map.Entry<Long, Long> day : basketByDay.entrySet()) {
            if (day.getKey() < today && day.getValue() > 0 && day.getKey() >= today - 7 && (baseDay == Long.MIN_VALUE
                    || day.getKey() < baseDay)) {
                baseDay = day.getKey();
            }
        }
        if (baseDay == Long.MIN_VALUE) {
            return OptionalDouble.empty();
        }
        double change = (now - basketByDay.get(baseDay)) * 100.0 / basketByDay.get(baseDay);
        return OptionalDouble.of(change * 7.0 / (today - baseDay));
    }

    @Override
    public String describe() {
        return "how the stabiliser turns the taps against inflation";
    }
}
