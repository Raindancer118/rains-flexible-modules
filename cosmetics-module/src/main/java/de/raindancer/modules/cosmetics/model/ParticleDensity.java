package de.raindancer.modules.cosmetics.model;

import java.util.Locale;
import java.util.Optional;

/** How thick a worn particle is drawn: particles per point of its shape. */
public enum ParticleDensity {

    LIGHT("Light", 1),
    NORMAL("Normal", 2),
    DENSE("Dense", 4),
    VERY_DENSE("Very dense", 6);

    private final String title;
    private final int count;

    ParticleDensity(String title, int count) {
        this.title = title;
        this.count = count;
    }

    public String title() {
        return title;
    }

    public int count() {
        return count;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ParticleDensity denser() {
        return values()[Math.min(values().length - 1, ordinal() + 1)];
    }

    public ParticleDensity lighter() {
        return values()[Math.max(0, ordinal() - 1)];
    }

    /** {@code dense}, {@code very_dense}, {@code very-dense}, {@code VERY DENSE}. */
    public static Optional<ParticleDensity> of(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String cleaned = typed.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ParticleDensity density : values()) {
            if (density.name().equals(cleaned)) {
                return Optional.of(density);
            }
        }
        return Optional.empty();
    }
}
