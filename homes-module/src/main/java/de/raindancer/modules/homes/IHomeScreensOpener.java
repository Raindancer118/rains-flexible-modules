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

    /**
     * Offers to buy another home slot, and on yes sets the home the player was trying to set.
     *
     * @param thenSet  whether to set a home once the slot is bought: true from {@code /sethome}, false
     *                 from the menu, where there is nothing to set afterwards
     * @param homeName what they typed to {@code /sethome}, null for the default name
     */
    void offerSlot(Player viewer, boolean thenSet, String homeName);
}
