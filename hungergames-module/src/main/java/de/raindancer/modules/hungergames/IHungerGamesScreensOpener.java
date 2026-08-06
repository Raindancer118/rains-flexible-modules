package de.raindancer.modules.hungergames;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built during Paper's bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class. It is also what lets a host plugin
 * put the module's pages behind its own hub.
 *
 * <p>Deliberately short. This module has around thirty screens and only these are entry points: everything
 * else is reached by clicking, from a page that already holds the services and can construct its own children.
 * A method here per screen would make this interface the module's menu tree written out a second time, and the
 * copy nothing enforces is the one that goes stale.
 */
public interface IHungerGamesScreensOpener {

    /**
     * The admin suite — {@code /hg admin}, the page a whole tournament is run from.
     *
     * <p>One entry point rather than one per section, because a gamemaster with forty people waiting should be
     * navigating by clicking rather than by remembering which subcommand opens the supply drops.
     */
    void admin(Player viewer);

    /** Picking a team, for a tribute in the lobby. */
    void teams(Player viewer);

    /** The sponsor shop, for a tribute spending tokens. */
    void shop(Player viewer);

    /** Where a spectator can go: the living, online tributes. */
    void spectate(Player viewer);

    /**
     * The border conflict page: what the phases as configured would do, and the ways out.
     *
     * <p>Its own entry point rather than a corner of the admin suite, because the thing that opens it is not a
     * click — it is a config change that has just been found to be impossible, and whoever made it has to be
     * shown the options then and there.
     */
    void borderConflict(Player viewer);
}
