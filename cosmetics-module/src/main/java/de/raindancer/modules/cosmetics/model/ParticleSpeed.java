package de.raindancer.modules.cosmetics.model;

import java.util.Locale;
import java.util.Optional;

/** How fast a worn particle's shape moves — the aura turning, the spiral climbing. */
public enum ParticleSpeed {

    SLOW("Slow", 0.5),
    NORMAL("Normal", 1.0),
    FAST("Fast", 2.0),
    VERY_FAST("Very fast", 3.0);

    private final String title;
    private final double factor;

    ParticleSpeed(String title, double factor) {
        this.title = title;
        this.factor = factor;
    }

    public String title() {
        return title;
    }

    /** How many animation frames pass per draw, relative to normal. */
    public double factor() {
        return factor;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ParticleSpeed faster() {
        return values()[Math.min(values().length - 1, ordinal() + 1)];
    }

    public ParticleSpeed slower() {
        return values()[Math.max(0, ordinal() - 1)];
    }

    /** {@code fast}, {@code very_fast}, {@code very-fast}, {@code VERY FAST}. */
    public static Optional<ParticleSpeed> of(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String cleaned = typed.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ParticleSpeed speed : values()) {
            if (speed.name().equals(cleaned)) {
                return Optional.of(speed);
            }
        }
        return Optional.empty();
    }
}
