package de.raindancer.modules.anticheat.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** One player's violation level per check. Decays lazily on every read, so nothing needs a timer. */
public final class Violations {

    private final LongSupplier clock;
    private final EnumMap<CheckType, double[]> levels = new EnumMap<>(CheckType.class);

    public Violations(LongSupplier clock) {
        this.clock = clock;
    }

    /** @return the level after adding */
    public synchronized double add(CheckType check, double amount) {
        double[] entry = decayed(check);
        entry[0] += Math.max(0, amount);
        return entry[0];
    }

    public synchronized double level(CheckType check) {
        return decayed(check)[0];
    }

    public synchronized double total() {
        double sum = 0;
        for (CheckType check : levels.keySet().toArray(CheckType[]::new)) {
            sum += decayed(check)[0];
        }
        return sum;
    }

    public synchronized void scale(CheckType check, double factor) {
        double[] entry = decayed(check);
        entry[0] *= Math.max(0, factor);
    }

    public synchronized void reset(CheckType check) {
        levels.remove(check);
    }

    public synchronized void resetAll() {
        levels.clear();
    }

    /** Checks with a level above zero, highest first. */
    public synchronized Map<CheckType, Double> snapshot() {
        Map<CheckType, Double> found = new EnumMap<>(CheckType.class);
        for (CheckType check : levels.keySet().toArray(CheckType[]::new)) {
            double level = decayed(check)[0];
            if (level > 1e-9) {
                found.put(check, level);
            }
        }
        Map<CheckType, Double> sorted = new LinkedHashMap<>();
        found.entrySet().stream()
                .sorted(Map.Entry.<CheckType, Double>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    /** {level, lastUpdatedMillis} with the decay since the last update applied. */
    private double[] decayed(CheckType check) {
        long now = clock.getAsLong();
        double[] entry = levels.computeIfAbsent(check, ignored -> new double[]{0, now});
        double minutes = Math.max(0, now - entry[1]) / 60_000.0;
        entry[0] = Math.max(0, entry[0] - minutes * check.decayPerMinute());
        entry[1] = now;
        return entry;
    }
}
