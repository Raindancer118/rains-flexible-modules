package de.raindancer.modules.cosmetics.model;

import de.raindancer.core.ui.effect.ParticleShape;

import java.util.Locale;

/**
 * The particle somebody wears: which, in what shape, and — for dust and the tinted ones — what colour.
 *
 * @param particle a {@code Particle} name, upper case; empty for none
 * @param colour   0xRRGGBB, or null where the particle takes none
 */
public record ParticleChoice(String particle, ParticleShape shape, Integer colour) {

    public static final ParticleChoice NONE = new ParticleChoice("", ParticleShape.AMBIENT, null);

    public ParticleChoice {
        particle = particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
        shape = shape == null ? ParticleShape.AMBIENT : shape;
    }

    public boolean isNone() {
        return particle.isEmpty();
    }

    public ParticleChoice withParticle(String next) {
        return new ParticleChoice(next, shape, colour);
    }

    public ParticleChoice withShape(ParticleShape next) {
        return new ParticleChoice(particle, next, colour);
    }

    public ParticleChoice withColour(Integer next) {
        return new ParticleChoice(particle, shape, next);
    }
}
