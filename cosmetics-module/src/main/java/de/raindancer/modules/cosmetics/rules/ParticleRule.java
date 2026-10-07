package de.raindancer.modules.cosmetics.rules;

import de.raindancer.core.platform.rule.Verdict;

import java.util.Locale;
import java.util.Set;

/** Whether somebody may wear a particle, and whether a worn one is drawn right now. */
public final class ParticleRule implements ICosmeticsRule {

    /**
     * @param showable whether the particle exists and needs nothing beyond a colour — asked of Core's
     *                 {@code ParticleShows.canShow} by the caller, so this stays a plain function
     */
    public Verdict judge(String particle, boolean mayUse, Set<String> blocked, boolean showable) {
        if (!mayUse) {
            return Verdict.refused("cosmetics.refused.particles");
        }
        String name = particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
        if (!showable) {
            return Verdict.refused("cosmetics.refused.particle-unknown", name.toLowerCase(Locale.ROOT));
        }
        if (blocked.contains(name)) {
            return Verdict.refused("cosmetics.refused.particle-blocked", name.toLowerCase(Locale.ROOT));
        }
        return Verdict.allowed();
    }

    /**
     * How many to draw per point: what they chose, or the server's default — never past the server's
     * ceiling, and never none.
     */
    public int count(de.raindancer.modules.cosmetics.model.ParticleDensity chosen, int serverDefault, int ceiling) {
        if (chosen == de.raindancer.modules.cosmetics.model.ParticleDensity.ULTRA) {
            // Past the ceiling on purpose: only somebody given the Ultra node can have chosen it.
            return chosen.count();
        }
        int wanted = chosen == null ? serverDefault : chosen.count();
        return Math.max(1, Math.min(Math.max(1, ceiling), wanted));
    }

    /** The density somebody may actually have: Ultra falls back to the densest below it without the node. */
    public de.raindancer.modules.cosmetics.model.ParticleDensity allowed(
            de.raindancer.modules.cosmetics.model.ParticleDensity chosen, boolean mayUltra) {
        return chosen == de.raindancer.modules.cosmetics.model.ParticleDensity.ULTRA && !mayUltra
                ? de.raindancer.modules.cosmetics.model.ParticleDensity.VERY_DENSE : chosen;
    }

    /**
     * The animation frame to draw at draw number {@code tick}: slow lingers on a frame, fast skips
     * ahead. Null is normal.
     */
    public long frame(long tick, de.raindancer.modules.cosmetics.model.ParticleSpeed speed) {
        return (long) Math.floor(tick * (speed == null ? 1.0 : speed.factor()));
    }

    /**
     * Whether this is drawn every tick: coloured wings, whose points live exactly one tick so a beating
     * wing never shows two frames at once. A particle without a colour cannot be given a lifetime, and
     * drawn every tick it would pile up — so it keeps the server's pace.
     */
    public boolean everyTick(de.raindancer.core.ui.effect.ParticleShape shape, boolean takesColour) {
        return shape.isWings() && takesColour;
    }

    /** Whether to draw on this game tick. */
    public boolean drawsNow(long tick, int every, boolean everyTick) {
        return everyTick || tick % Math.max(1, every) == 0;
    }

    /** The tick a shape's animation is at: one step per draw, however often that is. */
    public long animationTick(long tick, int every, boolean everyTick) {
        return everyTick ? tick : tick / Math.max(1, every);
    }

    /** Somebody hidden must not give themselves away by a ring of flames. */
    public boolean shows(boolean particlesOn, boolean vanished, boolean spectating, boolean invisible,
                         boolean dead) {
        return particlesOn && !vanished && !spectating && !invisible && !dead;
    }

    @Override
    public String describe() {
        return "who may wear which particle, and when it is drawn";
    }
}
