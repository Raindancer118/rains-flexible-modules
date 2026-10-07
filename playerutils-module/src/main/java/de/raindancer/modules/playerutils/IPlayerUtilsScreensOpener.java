package de.raindancer.modules.playerutils;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Opening the module's screens, so nothing else depends on them. */
public interface IPlayerUtilsScreensOpener {

    /** The tools for one player. */
    void tools(Player viewer, UUID target);

    /** Pick somebody, then their tools. */
    void choose(Player viewer);

    /** The effects on somebody. */
    void effects(Player viewer, UUID target);
}
