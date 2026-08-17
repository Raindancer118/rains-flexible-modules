package de.raindancer.modules.wallsroads.rules;

import de.raindancer.modules.wallsroads.util.PermissionNodes;
import org.bukkit.entity.Player;

/** Whether a player may mark a new wall or road out — asked speculatively, decides nothing itself. */
public final class CreateRule {

    public boolean mayCreate(boolean openCreation, Player player) {
        return openCreation || player.hasPermission(PermissionNodes.CREATE);
    }
}
