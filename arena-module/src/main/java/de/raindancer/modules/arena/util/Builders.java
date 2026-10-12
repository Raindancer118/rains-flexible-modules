package de.raindancer.modules.arena.util;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Who is building rather than playing: an admin in creative. Everybody else changes nothing in the arena. */
public final class Builders {

    private Builders() {
    }

    public static boolean isBuilding(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(PermissionNodes.ADMIN);
    }
}
