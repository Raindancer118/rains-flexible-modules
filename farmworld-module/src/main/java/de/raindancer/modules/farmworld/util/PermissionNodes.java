package de.raindancer.modules.farmworld.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent — two copies of the
 * module on one server is a real state — and it happens before anything asks, since an unregistered
 * node resolves to "operators only" and would refuse the farm world to every ordinary player.
 *
 * <h2>The per-farm-world node, and why it is derived rather than stored</h2>
 * A server with two farm worlds usually wants both open to everybody; a server with a third one for
 * whoever paid for it wants that one gated. Both are the same question about a permission, so the
 * node is <b>worked out from the name</b> — {@code rainsfarmworlds.world.<name>} — and nothing is
 * stored anywhere.
 *
 * <p>That matters more than it looks. Core's {@code WorldSet} has no field for a permission, so
 * storing one would mean a second file of the module's own beside Core's {@code farmworlds.yml}: two
 * records of which farm worlds exist, and a farm world renamed in one of them and not the other. The
 * derived node has nowhere to drift to.
 *
 * <p>It defaults to <b>true</b>, so a server that grants nothing has farm worlds anybody can enter,
 * and an owner who wants one closed negates the single node in their permissions plugin. The
 * alternative — default false — is a farm world that silently exists and nobody can reach, which is
 * reported as the plugin being broken.
 */
public final class PermissionNodes {

    /** Entering a farm world at all. On by default: a farm world nobody can enter is not one. */
    public static final String USE = "rainsfarmworlds.farm.use";

    /** Making farm worlds, changing them, and regenerating one by hand. */
    public static final String MANAGE = "rainsfarmworlds.farm.manage";

    /** What a single farm world's own node is prefixed with. */
    public static final String WORLD_PREFIX = "rainsfarmworlds.world.";

    private PermissionNodes() {
    }

    /**
     * The node that opens one named farm world.
     *
     * <p>Anything that is not a permission character is dropped rather than escaped: a node with a
     * space in it can be granted and will never match, which reads to an admin as a permissions
     * plugin that has stopped working. A farm world's name cannot contain one — {@code WorldSet}
     * refuses it, because the name becomes a folder — so this only ever tidies.
     */
    public static String forWorld(String name) {
        if (name == null || name.isBlank()) {
            return MANAGE;
        }
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        // Never a bare prefix: that is a node nobody can be granted, so the farm world would be
        // reachable by nobody and there would be nothing on screen to say why.
        return cleaned.isEmpty() ? MANAGE : WORLD_PREFIX + cleaned;
    }

    /** The two fixed nodes. The per-world ones are added by {@link #declared(List)}. */
    public static List<Permission> declared() {
        return declared(List.of());
    }

    /**
     * The nodes, including one per farm world that exists.
     *
     * @param worldNames the farm worlds on this server, so their nodes show up in a permissions
     *                   plugin's list of known nodes. Without that an admin has to know the string
     *                   from the documentation to negate it, and closing one farm world is the first
     *                   thing anybody wants to do with more than one
     */
    public static List<Permission> declared(List<String> worldNames) {
        List<Permission> all = new ArrayList<>();
        all.add(new Permission(USE,
                "Enter a farm world",
                PermissionDefault.TRUE));
        all.add(new Permission(MANAGE,
                "Make and change farm worlds, and throw one away by hand",
                PermissionDefault.OP));
        for (String name : worldNames == null ? List.<String>of() : worldNames) {
            String node = forWorld(name);
            if (node.equals(MANAGE)) {
                continue;
            }
            all.add(new Permission(node,
                    "Enter the farm world called " + name,
                    PermissionDefault.TRUE));
        }
        return List.copyOf(all);
    }

    /**
     * Registers whatever is not registered already.
     *
     * @return how many were added, for the line in the log
     */
    public static int register(Server server, List<String> worldNames) {
        if (server == null) {
            return 0;
        }
        int added = 0;
        for (Permission permission : declared(worldNames)) {
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
