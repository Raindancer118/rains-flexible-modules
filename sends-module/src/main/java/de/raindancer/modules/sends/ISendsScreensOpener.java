package de.raindancer.modules.sends;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it — so a command, built at bootstrap
 * long before any menu could be constructed, can name what it wants to open.
 */
public interface ISendsScreensOpener {

    /** Everything waiting for the viewer, and everything they have waiting for others. */
    void sends(Player viewer);

    /** Pick somebody online, then fill a window for them. */
    void choose(Player viewer);

    /** The window to put items in, for somebody already picked. */
    void compose(Player viewer, java.util.UUID recipient, String recipientName);
}
