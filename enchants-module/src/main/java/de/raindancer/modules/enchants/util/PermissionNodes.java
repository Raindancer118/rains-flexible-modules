package de.raindancer.modules.enchants.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Handing out enchanted books with /enchants give. */
    public static final String GIVE = "rainsenchants.give";
    /** Calling a creature of the shard world to somebody with /enchants shardmob — more than handing out items. */
    public static final String SUMMON = "rainsenchants.shardmob";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(new Permission(GIVE, "Give enchanted books with /enchants give", PermissionDefault.OP),
                new Permission(SUMMON, "Call a creature of the shard world to a player with /enchants shardmob",
                        PermissionDefault.OP));
    }

    public static int register(Server server) {
        int added = 0;
        for (Permission permission : declared()) {
            if (server.getPluginManager().getPermission(permission.getName()) == null) {
                try {
                    server.getPluginManager().addPermission(permission);
                    added++;
                } catch (IllegalArgumentException alreadyThere) {
                    // Registered by another copy between the check and the add.
                }
            }
        }
        return added;
    }
}
