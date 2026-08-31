package de.raindancer.modules.manhunt;

import org.bukkit.entity.Player;

/** Opening a screen, so a command can ask for one without knowing the menu classes exist. */
public interface IManhuntScreensOpener {

    /** The lobby: which side {@code viewer} is on, the clock, and a way to switch sides. */
    void lobby(Player viewer);

    /** The chaos menu: one button per {@code ChaosAction}. */
    void chaos(Player viewer);
}
