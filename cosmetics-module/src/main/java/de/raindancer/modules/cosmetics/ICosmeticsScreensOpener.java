package de.raindancer.modules.cosmetics;

import org.bukkit.entity.Player;

/** Opening a screen, so commands and services do not depend on the menu classes. */
public interface ICosmeticsScreensOpener {

    void hub(Player viewer);

    void nameStyle(Player viewer);

    void particles(Player viewer);
}
