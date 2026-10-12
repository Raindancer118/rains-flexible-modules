package de.raindancer.modules.arena;

import de.raindancer.modules.arena.model.NpcRole;
import org.bukkit.entity.Player;

/** Opening this module's screens, so nothing else depends on the menu classes. */
public interface IArenaScreensOpener {

    void main(Player viewer);

    void forRole(Player viewer, NpcRole role);
}
