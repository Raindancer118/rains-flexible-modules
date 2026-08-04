package de.raindancer.modules.warp;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class. It is also what lets a
 * host open the module's pages from its own hub.
 */
public interface IWarpScreensOpener {

    /** The warp list: every warp this player may use, in one page they can click. */
    void warps(Player viewer);

    /** The same, showing one category only. */
    void category(Player viewer, String category);

    /** The categories page, for a server with more warps than fit on a list. */
    void categories(Player viewer);

    /** The admin list: every warp there is, and what can be changed about each. */
    void admin(Player viewer);

    /** One warp's own page, for an admin. */
    void edit(Player viewer, String warpName);

    /** What this server does about warps: the admin's own settings page. */
    void config(Player viewer);
}
