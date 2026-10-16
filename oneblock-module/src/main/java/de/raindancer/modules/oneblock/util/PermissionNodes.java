package de.raindancer.modules.oneblock.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody, registered in code so a hosted module still has its nodes. Everybody may
 * invite and go to their island; who may invite whom is the island's own team. {@link #ADMIN} is staff's: holding
 * islands up or down, the admin pages, entering any island, and never being kept off one.
 */
public final class PermissionNodes {

    public static final String USE = "rainsoneblock.use";
    public static final String ADMIN = "rainsoneblock.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(new Permission(USE, "Go to your OneBlock island and invite players from anywhere on the network",
                PermissionDefault.TRUE),
                new Permission(ADMIN, "Hold islands up or down, switch their auto-unload, enter any island and move "
                        + "islands over from BentoBox", PermissionDefault.OP));
    }

    /** @return how many were added, for the line in the log */
    public static int register(Server server) {
        if (server == null) {
            return 0;
        }
        int added = 0;
        for (Permission permission : declared()) {
            if (server.getPluginManager().getPermission(permission.getName()) != null) {
                continue;
            }
            try {
                server.getPluginManager().addPermission(permission);
                added++;
            } catch (IllegalArgumentException alreadyThere) {
                // Registered by another copy between the check and the add.
            }
        }
        return added;
    }
}
