package de.raindancer.modules.cosmetics.model;

import de.raindancer.core.ui.effect.ParticleShape;

import java.util.Locale;

/**
 * The particle somebody wears: which, in what shape, and — for dust and the tinted ones — what colour.
 *
 * @param particle a {@code Particle} name, upper case; empty for none
 * @param colour   0xRRGGBB, or null where the particle takes none
 * @param density  how thick it is drawn; null for the server's default
 * @param speed    how fast its shape moves; null for normal
 * @param colourTo the other end of a gradient from {@code colour} along the shape; null for one colour
 * @param natural  drawn as Minecraft draws the particle rather than as crisp points — see {@link #withNatural}
 */
public record ParticleChoice(String particle, ParticleShape shape, Integer colour, ParticleDensity density,
                             ParticleSpeed speed, Integer colourTo, boolean natural) {

    public static final ParticleChoice NONE = new ParticleChoice("", ParticleShape.AMBIENT, null, null, null, null);

    public ParticleChoice(String particle, ParticleShape shape, Integer colour, ParticleDensity density,
                          ParticleSpeed speed) {
        this(particle, shape, colour, density, speed, null);
    }

    public ParticleChoice(String particle, ParticleShape shape, Integer colour, ParticleDensity density,
                          ParticleSpeed speed, Integer colourTo) {
        this(particle, shape, colour, density, speed, colourTo, false);
    }

    /** Without a density of its own: drawn at the server's default. */
    public ParticleChoice(String particle, ParticleShape shape, Integer colour) {
        this(particle, shape, colour, null, null);
    }

    public ParticleChoice(String particle, ParticleShape shape, Integer colour, ParticleDensity density) {
        this(particle, shape, colour, density, null);
    }

    public ParticleChoice {
        particle = particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
        shape = shape == null ? ParticleShape.AMBIENT : shape;
    }

    public boolean isNone() {
        return particle.isEmpty();
    }

    public ParticleChoice withParticle(String next) {
        return new ParticleChoice(next, shape, colour, density, speed, colourTo, natural);
    }

    public ParticleChoice withShape(ParticleShape next) {
        return new ParticleChoice(particle, next, colour, density, speed, colourTo, natural);
    }

    public ParticleChoice withColour(Integer next) {
        return new ParticleChoice(particle, shape, next, density, speed, colourTo, natural);
    }

    public ParticleChoice withDensity(ParticleDensity next) {
        return new ParticleChoice(particle, shape, colour, next, speed, colourTo, natural);
    }

    /** The other end of a gradient; null back to one colour. */
    public ParticleChoice withColourTo(Integer next) {
        return new ParticleChoice(particle, shape, colour, density, speed, next, natural);
    }

    /**
     * Drawn as Minecraft draws the particle — a flame rises, a leaf falls — rather than as crisp points
     * that hold their place. Only matters for wings, which are otherwise crisp wherever they can be.
     */
    public ParticleChoice withNatural(boolean next) {
        return new ParticleChoice(particle, shape, colour, density, speed, colourTo, next);
    }

    public ParticleChoice withSpeed(ParticleSpeed next) {
        return new ParticleChoice(particle, shape, colour, density, next, colourTo, natural);
    }
}
