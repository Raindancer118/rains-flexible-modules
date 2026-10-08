package de.raindancer.modules.moderation.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much of each ore the world itself holds, measured by sampling chunks, never by watching
 * players — a baseline learnt from players would learn x-ray as normal once enough people use it.
 *
 * <p>Counted among <em>enclosed</em> solid blocks only (no face open to air or liquid), because that
 * is exactly what digging reveals: the chance that a block a tunnel newly exposes is ore.
 */
public final class OreDensity {

    /** Below this many sampled blocks a band's rate is not trusted. */
    public static final long TRUSTED_SAMPLE = 20_000;
    private static final long CAP = 50_000_000;

    private final Map<String, Counts> bands = new ConcurrentHashMap<>();

    public static final class Counts {
        long enclosed;
        final EnumMap<OreKind, Long> ores = new EnumMap<>(OreKind.class);

        public synchronized long enclosed() {
            return enclosed;
        }

        public synchronized long ores(OreKind kind) {
            return ores.getOrDefault(kind, 0L);
        }
    }

    public void add(RockBand band, long enclosed, Map<OreKind, Long> ores) {
        Counts counts = bands.computeIfAbsent(band.key(), ignored -> new Counts());
        synchronized (counts) {
            counts.enclosed += enclosed;
            ores.forEach((kind, count) -> counts.ores.merge(kind, count, Long::sum));
            if (counts.enclosed > CAP) {
                counts.enclosed /= 2;
                counts.ores.replaceAll((kind, count) -> count / 2);
            }
        }
    }

    /** The share of enclosed blocks that are this ore, or NaN while the band is not sampled well enough. */
    public double rate(RockBand band, OreKind kind) {
        Counts counts = bands.get(band.key());
        if (counts == null) {
            return Double.NaN;
        }
        synchronized (counts) {
            if (counts.enclosed < TRUSTED_SAMPLE) {
                return Double.NaN;
            }
            return (double) counts.ores.getOrDefault(kind, 0L) / counts.enclosed;
        }
    }

    public long sampled(RockBand band) {
        Counts counts = bands.get(band.key());
        return counts == null ? 0 : counts.enclosed();
    }

    public Map<String, Counts> all() {
        return Map.copyOf(bands);
    }

    public void clear() {
        bands.clear();
    }
}
