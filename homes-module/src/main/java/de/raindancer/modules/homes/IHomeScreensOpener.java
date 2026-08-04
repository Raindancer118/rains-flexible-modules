package de.raindancer.modules.homes;

import de.raindancer.modules.homes.model.Home;
import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be constructed
 * — can name what it wants to open without naming a menu class. It is also what lets a host open the
 * module's pages from its own hub.
 */
public interface IHomeScreensOpener {

    /** The list: every home this player has, in one page they can click. */
    void homes(Player viewer);

    /** One home's own page: go, rename, re-icon, delete. */
    void edit(Player viewer, Home home);

    /** The icon picker for one home. */
    void icon(Player viewer, Home home);
}
