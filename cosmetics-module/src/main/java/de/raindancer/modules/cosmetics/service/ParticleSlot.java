package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
import org.bukkit.entity.Player;

/**
 * One particle a player sets up on the particle page: the one they wear, or the one around them
 * while they wait for a teleport. One page edits both, so both get the same shape, colour, density
 * and preview.
 */
public interface ParticleSlot {

    /** The page's title and breadcrumb. */
    String heading();

    ParticleChoice current(Player who);

    boolean mayUse(Player who);

    /** Shown on a button they may not press. */
    String locked();

    ParticleCatalogue catalogue();

    boolean wear(Player who, String particle);

    /** @return false when it was refused, and they were told why — wings somebody else reserved */
    boolean shape(Player who, ParticleShape shape);

    /** @return false when it was refused, and they were told why */
    boolean colour(Player who, int rgb);

    /** The other end of a gradient; null back to one colour. @return false when it was refused */
    boolean colourTo(Player who, Integer rgb);

    /** Whether {@code who} may pick the Ultra density. */
    boolean mayUltra(Player who);

    ParticleDensity densityOf(Player who);

    void density(Player who, ParticleDensity density);

    /** Whether this density is held down by the server's ceiling. */
    boolean isCapped(ParticleDensity density);

    /** Whether the shape's speed can be chosen here. */
    boolean hasSpeed();

    ParticleSpeed speedOf(Player who);

    void speed(Player who, ParticleSpeed speed);

    /** The "take it off" button: its title and what it does. */
    String takeOffTitle();

    void takeOff(Player who);

    void preview(Player who);

    /** Whether this is the worn particle, which has the "see other people's" switch beside it. */
    boolean isWorn();

    /** Whether this slot holds wings, and only wings — its shape row offers the kinds of wings. */
    default boolean wingsOnly() {
        return false;
    }

    /** Whether it may be drawn naturally, as Minecraft draws the particle, rather than crisp. */
    default boolean hasStyle() {
        return false;
    }

    default void natural(Player who, boolean natural) {
    }
}
