package de.raindancer.modules.tpa;

import de.raindancer.modules.tpa.model.TpaKind;
import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be constructed
 * — can name what it wants to open without naming a menu class. It is also what lets a host open the
 * module's pages from its own hub.
 */
public interface ITpaScreensOpener {

    /** The hub: everything this module can do, as buttons. */
    void hub(Player viewer);

    /** Somebody to ask, in the given direction. */
    void whoToAsk(Player viewer, TpaKind kind);

    /** What has been asked of this player, and what they have asked. */
    void requests(Player viewer);

    /** Who they have blocked. */
    void blocked(Player viewer);
}
