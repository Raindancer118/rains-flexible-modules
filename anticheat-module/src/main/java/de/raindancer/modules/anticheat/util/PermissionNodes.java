package de.raindancer.modules.anticheat.util;

import de.raindancer.modules.anticheat.model.CheckType;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    public static final String COMMAND = "rainsanticheat.command";
    public static final String ALERTS = "rainsanticheat.alerts";
    public static final String MANAGE = "rainsanticheat.manage";
    /** Not given to operators by default: an op who flies is still checked unless somebody decides otherwise. */
    public static final String BYPASS = "rainsanticheat.bypass";
    public static final String BYPASS_PREFIX = "rainsanticheat.bypass.";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        List<Permission> all = new ArrayList<>(List.of(
                new Permission(COMMAND, "Use /anticheat to look players up", PermissionDefault.OP),
                new Permission(ALERTS, "Be told when somebody fails a check", PermissionDefault.OP),
                new Permission(MANAGE, "Exempt players, clear their record and switch checks", PermissionDefault.OP),
                new Permission(BYPASS, "Never be checked at all", PermissionDefault.FALSE)));
        for (CheckType check : CheckType.values()) {
            all.add(new Permission(BYPASS_PREFIX + check.key(), "Never be checked for " + check.title(), PermissionDefault.FALSE));
        }
        return all;
    }

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
