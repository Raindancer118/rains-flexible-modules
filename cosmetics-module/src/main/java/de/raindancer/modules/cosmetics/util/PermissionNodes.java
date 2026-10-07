package de.raindancer.modules.cosmetics.util;

import de.raindancer.modules.cosmetics.model.Preset;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every node, with its default — registered in code because a module may be hosted in somebody else's
 * plugin and has no descriptor of its own. Unregistered, a node defaults to operators only.
 *
 * <p>The defaults make name colours a thing everybody has; a server that wants them as a rank perk sets
 * the nodes to false in its permission plugin and grants them to the rank. Obfuscated is operators
 * only: a name nobody can read is a way to dodge reports.
 */
public final class PermissionNodes {

    public static final String USE = "rainscosmetics.use";
    public static final String NAME_COLOUR = "rainscosmetics.name.colour";
    public static final String NAME_GRADIENT = "rainscosmetics.name.gradient";
    public static final String NAME_ANY_COLOUR = "rainscosmetics.name.any-colour";
    public static final String NAME_ANIMATED = "rainscosmetics.name.animated";
    public static final String DECORATION_PREFIX = "rainscosmetics.name.decoration.";
    /** Every restricted preset at once. Checked by hand as well, since Bukkit has no wildcards. */
    public static final String PRESET_ALL = Preset.PERMISSION_PREFIX + "*";
    public static final String PARTICLES = "rainscosmetics.particles";
    /** The Ultra density: hundreds of particles per draw, so not for everybody. */
    public static final String PARTICLES_ULTRA = "rainscosmetics.particles.ultra";
    public static final String TELEPORT = "rainscosmetics.teleport";
    /** Any sound for a teleport, not only the server's list. Loud ones included, so for staff. */
    public static final String TELEPORT_ANY_SOUND = "rainscosmetics.teleport.any-sound";
    public static final String CLEAR = "rainscosmetics.clear";
    public static final String CLEAR_OTHERS = "rainscosmetics.clear.others";
    public static final String ADMIN = "rainscosmetics.admin";

    private PermissionNodes() {
    }

    public static String decoration(TextDecoration decoration) {
        return DECORATION_PREFIX + decoration.name().toLowerCase(Locale.ROOT);
    }

    public static List<Permission> declared() {
        List<Permission> nodes = new ArrayList<>();
        nodes.add(new Permission(USE, "Open /cosmetics and wear public presets", PermissionDefault.TRUE));
        nodes.add(new Permission(NAME_COLOUR, "Paint your name in one palette colour", PermissionDefault.TRUE));
        nodes.add(new Permission(NAME_GRADIENT, "Paint your name in a gradient of several colours",
                PermissionDefault.TRUE));
        nodes.add(new Permission(NAME_ANY_COLOUR, "Use colours outside the palette, typed as #hex",
                PermissionDefault.TRUE));
        nodes.add(new Permission(NAME_ANIMATED, "Let your gradient flow along your name",
                PermissionDefault.TRUE));
        for (TextDecoration decoration : TextDecoration.values()) {
            nodes.add(new Permission(decoration(decoration),
                    "Make your name " + decoration.name().toLowerCase(Locale.ROOT),
                    decoration == TextDecoration.OBFUSCATED ? PermissionDefault.OP : PermissionDefault.TRUE));
        }
        nodes.add(new Permission(PARTICLES, "Wear a particle effect", PermissionDefault.TRUE));
        nodes.add(new Permission(PARTICLES_ULTRA, "Draw particles at Ultra density, past the server's ceiling",
                PermissionDefault.OP));
        nodes.add(new Permission(TELEPORT, "Choose your own teleport sounds and waiting particles",
                PermissionDefault.TRUE));
        nodes.add(new Permission(TELEPORT_ANY_SOUND, "Choose any sound for your teleports, not only the server's list",
                PermissionDefault.OP));
        nodes.add(new Permission(CLEAR, "Take your own name style and particle off with /cosmetics clear",
                PermissionDefault.TRUE));
        nodes.add(new Permission(CLEAR_OTHERS, "Take somebody else's name style and particle off",
                PermissionDefault.OP));
        nodes.add(new Permission(PRESET_ALL, "Wear every restricted preset", PermissionDefault.OP));
        nodes.add(new Permission(ADMIN, "Reset somebody else's name style, reload the palette",
                PermissionDefault.OP));
        return nodes;
    }

    /** One node per restricted preset, operators by default. Called again after a reload adds some. */
    public static List<Permission> declaredFor(List<Preset> presets) {
        List<Permission> nodes = new ArrayList<>();
        for (Preset preset : presets) {
            if (preset.restricted()) {
                nodes.add(new Permission(preset.permission(), "Wear the " + preset.title() + " preset",
                        PermissionDefault.OP));
            }
        }
        return nodes;
    }

    /** Registers what is not registered yet. Idempotent. */
    public static int register(Server server, List<Permission> nodes) {
        if (server == null) {
            return 0;
        }
        int added = 0;
        for (Permission permission : nodes) {
            if (server.getPluginManager().getPermission(permission.getName()) != null) {
                continue;
            }
            try {
                server.getPluginManager().addPermission(permission);
                added++;
            } catch (IllegalArgumentException alreadyThere) {
                // Registered by another copy of the module in between — it exists, which is the point.
            }
        }
        return added;
    }
}
