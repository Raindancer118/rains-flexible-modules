package de.raindancer.modules.rtp;

import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class.
 */
public interface IRtpScreensOpener {

    /**
     * Safe landing, or take your chances — asked only when the settings leave it up to the player.
     *
     * @param minDistance the same override {@code /rtp <distance>} would pass to
     *                    {@code RtpService#go} directly; null when nobody asked for one
     */
    void chooser(Player viewer, Integer minDistance);
}
