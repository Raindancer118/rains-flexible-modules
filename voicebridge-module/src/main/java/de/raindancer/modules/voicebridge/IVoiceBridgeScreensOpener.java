package de.raindancer.modules.voicebridge;

import org.bukkit.entity.Player;

/** Opening this module's screen without the command — built at bootstrap — naming a menu class. */
public interface IVoiceBridgeScreensOpener {

    void root(Player viewer);
}
