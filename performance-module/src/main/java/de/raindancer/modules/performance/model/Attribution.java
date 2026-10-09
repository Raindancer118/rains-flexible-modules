package de.raindancer.modules.performance.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Samples of the server thread, counted by cause. Filled by one sampler, then only read. */
public final class Attribution {

    public record Share(Cause cause, int samples, double percent) {
    }

    private final Map<Cause, Integer> counts = new LinkedHashMap<>();

    public synchronized void count(Cause cause) {
        counts.merge(cause, 1, Integer::sum);
    }

    /** Samples where the thread was doing something — waiting for the next tick is not. */
    public synchronized int busySamples() {
        return counts.entrySet().stream().filter(each -> each.getKey().area() != Cause.Area.IDLE)
                .mapToInt(Map.Entry::getValue).sum();
    }

    public synchronized int samples() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Busy causes with their share of the busy time, biggest first. */
    public synchronized List<Share> ranked() {
        int busy = busySamples();
        List<Share> shares = new ArrayList<>();
        counts.forEach((cause, samples) -> {
            if (cause.area() != Cause.Area.IDLE) {
                shares.add(new Share(cause, samples, 100.0 * samples / busy));
            }
        });
        shares.sort(Comparator.comparingInt(Share::samples).reversed());
        return shares;
    }

    /** The share of these vanilla areas together, in percent of the busy time. */
    public synchronized double percentOf(Cause.Area... areas) {
        int busy = busySamples();
        if (busy == 0) {
            return 0;
        }
        int sum = 0;
        for (Cause.Area area : areas) {
            sum += counts.getOrDefault(Cause.vanilla(area), 0);
        }
        return 100.0 * sum / busy;
    }
}
