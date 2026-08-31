package de.raindancer.modules.manhunt;

import org.bukkit.entity.Player;

/** Opening a screen, so a command can ask for one without knowing the menu classes exist. */
public interface IManhuntScreensOpener {

    /** The lobby: which side {@code viewer} is on, the clock, and a way to switch sides. */
    void lobby(Player viewer);

    /** The chaos menu: one button per {@code ChaosAction}. */
    void chaos(Player viewer);

    /** The achievements menu: the curated set, with icons, earned or not. */
    void achievements(Player viewer);

    /** The options menu: a curated quick-access subset of ManhuntSettings, with icons. */
    void options(Player viewer);

    /** The tracking compass' own page: every one of its settings, with icons. */
    void tracker(Player viewer);

    /** What a hunt is like to be in: narration, side chat, the borrowed rules, spectators. */
    void field(Player viewer);
}
