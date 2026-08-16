package de.raindancer.modules.invsnap;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class.
 */
public interface IInvSnapScreensOpener {

    /** A target player's snapshot history, for the admin viewing it. */
    void history(Player admin, UUID target, String targetName);

    /** Every player this server has a snapshot of, for an admin who does not name one. */
    void root(Player admin);
}
