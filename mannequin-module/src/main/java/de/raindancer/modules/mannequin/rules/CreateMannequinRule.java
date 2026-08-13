package de.raindancer.modules.mannequin.rules;

import de.raindancer.modules.mannequin.util.PermissionNodes;
import org.bukkit.permissions.Permissible;

/**
 * Whether somebody may create a mannequin.
 *
 * <p>{@code openCreation} true means anybody may — the node exists only for a server that wants
 * to lock creation down. False means it is exactly the node: {@link PermissionNodes#CREATE}.
 */
public final class CreateMannequinRule implements IMannequinRule {

    public boolean mayCreate(boolean openCreation, Permissible player) {
        if (openCreation) {
            return true;
        }
        return player != null && player.hasPermission(PermissionNodes.CREATE);
    }

    @Override
    public String describe() {
        return "whether creation is open to anybody, or gated behind the create permission";
    }
}
