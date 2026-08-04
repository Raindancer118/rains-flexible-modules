package de.raindancer.modules.names;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — which is built at bootstrap, before any of the menus could be
 * constructed — can name what it wants to open without naming a menu class. It is also what lets a host
 * open the module's pages from its own hub.
 */
public interface INamesScreensOpener {

    /** The manual: every dye, decoration and shade this server has, painted in itself. */
    void manual(Player viewer);
}
