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
