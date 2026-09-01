package de.raindancer.modules.xpbottle;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class.
 */
public interface IXpBottleScreensOpener {

    /** The module's own page: what a bottle would hold, and where a siphon comes from. */
    void root(Player viewer);
}
