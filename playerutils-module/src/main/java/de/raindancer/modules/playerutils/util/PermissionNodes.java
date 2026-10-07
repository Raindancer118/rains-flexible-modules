package de.raindancer.modules.playerutils.util;

import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.rules.TargetRule;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every node, declared with a default so a server without a permissions plugin works out of the box:
 * operators can do everything, everybody can ask about themselves.
 */
public final class PermissionNodes {

    public static final String SELECTORS = "rainsplayerutils.selectors";
    public static final String LETHAL = "rainsplayerutils.damage.lethal";
    public static final String EXPLODE_BLOCKS = "rainsplayerutils.explode.blocks";
    public static final String EXPLODE_FIRE = "rainsplayerutils.explode.fire";
    public static final String SUDO_OPS = "rainsplayerutils.sudo.ops";
    public static final String TOOLS = "rainsplayerutils.tools";
    public static final String ALL = "rainsplayerutils.*";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        List<Permission> nodes = new ArrayList<>();
        for (Action action : Action.values()) {
            PermissionDefault self = switch (action.self()) {
                case EVERYBODY -> PermissionDefault.TRUE;
                case OP, NEVER -> PermissionDefault.OP;
            };
            if (action == Action.SPECTATE || action == Action.SUDO) {
                self = PermissionDefault.OP;
            }
            nodes.add(new Permission(action.node(), action.describe(), self));
            // Asking how somebody else is doing is harmless; telling where they are is not.
            PermissionDefault others = action == Action.PING ? PermissionDefault.TRUE : PermissionDefault.OP;
            nodes.add(new Permission(action.othersNode(), action.describe() + " — to somebody else", others));
        }
        nodes.add(new Permission(TargetRule.EXEMPT,
                "Nothing in Player Utils that hurts or takes away can be pointed at you", PermissionDefault.FALSE));
        nodes.add(new Permission(TargetRule.BYPASS,
                "May hurt or take from exempt players anyway", PermissionDefault.FALSE));
        nodes.add(new Permission(SELECTORS, "Aim actions with @a, @p, @r and @e[...]", PermissionDefault.OP));
        nodes.add(new Permission(LETHAL, "/damage ... lethal may kill", PermissionDefault.OP));
        nodes.add(new Permission(EXPLODE_BLOCKS, "/explode ... blocks may break blocks", PermissionDefault.OP));
        nodes.add(new Permission(EXPLODE_FIRE, "/explode ... fire may start fires", PermissionDefault.OP));
        nodes.add(new Permission(SUDO_OPS, "/sudo may be pointed at operators", PermissionDefault.FALSE));
        nodes.add(new Permission(TOOLS, "Open the player tools page with /player", PermissionDefault.OP));
        Map<String, Boolean> everything = new java.util.LinkedHashMap<>();
        for (Permission node : nodes) {
            if (!node.getName().equals(TargetRule.EXEMPT) && !node.getName().equals(SUDO_OPS)
                    && !node.getName().equals(TargetRule.BYPASS)) {
                everything.put(node.getName(), true);
            }
        }
        nodes.add(new Permission(ALL, "Everything in Player Utils except exemption, bypass and sudo on ops",
                PermissionDefault.OP, everything));
        return nodes;
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
                // Registered by another copy between the check and the add. Nothing to do.
            }
        }
        return added;
    }
}
