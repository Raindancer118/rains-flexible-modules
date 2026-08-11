package de.raindancer.modules.chained;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class.
 */
public interface IChainedScreensOpener {

    /** The viewer's own pair and its clock. */
    void status(Player viewer);

    /** Pairing, starting, stopping and resetting the map. */
    void admin(Player viewer);
}
