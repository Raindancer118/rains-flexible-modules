package de.raindancer.modules.farmworld;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be constructed —
 * can name what it wants to open without naming a menu class. It is also what lets a host open the
 * module's pages from its own hub.
 */
public interface IFarmWorldScreensOpener {

    /** The farm worlds on this server, in one page they can click. */
    void farms(Player viewer);

    /** One farm world's own page: what it is, when it goes, and the way in. */
    void farm(Player viewer, String name);

    /** One farm world's settings, for an admin: its schedule, its border, its dimensions. */
    void manage(Player viewer, String name);

    /** What this server does about farm worlds: the admin's own settings page. */
    void config(Player viewer);
}
