package de.raindancer.modules.claims.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telling the server which claim permissions exist, and who has them without being granted anything.
 *
 * <h2>Why this class had to be written</h2>
 * The plugin this module replaced declared its permissions in {@code paper-plugin.yml}, with
 * {@code rec.use: default: true} — which is why an ordinary player could make a claim on a server where
 * they were not an operator.
 *
 * <p>A module has no descriptor of its own, because it may be hosted inside somebody else's plugin. So
 * nothing declared them, and Bukkit resolves an unregistered permission against
 * {@code Permission.DEFAULT_PERMISSION}, which is {@code OP}. {@code hasPermission("rec.use")} therefore
 * answered <b>false</b> for every player, and {@code /claim} silently became an operator command: it
 * built, it started, it logged nothing, and the only person able to test it was the one person for whom
 * it worked.
 *
 * <p>Registering programmatically is the only form that works in both arrangements, hosted and
 * standalone, which is why the defaults live here rather than in a file.
 *
 * <h2>The defaults are the old ones, deliberately</h2>
 * Including the awkward one: {@code rec.admin.nofee} is {@code false} and is <em>not</em> a child of
 * {@code rec.admin}, because an admin walking around the server should pay a claim's toll like everybody
 * else — or switch the protection bypass on for as long as they are actually working. An owner whose own
 * entry fees quietly do nothing stops noticing that they are configured at all.
 */
public final class ClaimPermissions {

    private ClaimPermissions() {
    }

    /**
     * Every permission this module understands, with its default and its children.
     *
     * <p>Built rather than registered, so the defaults can be asserted without a server — see
     * {@code PermissionDefaultsTest}. {@link #register} is the half that needs one.
     */
    public static List<Permission> declared() {
        List<Permission> permissions = new ArrayList<>();

        permissions.add(new Permission("rec.use", "Use /claim and create claims",
                PermissionDefault.TRUE));

        // The children matter: granting rec.admin alone must not leave somebody unable to run /claim.
        Map<String, Boolean> adminImplies = new LinkedHashMap<>();
        adminImplies.put("rec.use", true);
        adminImplies.put("rec.bypass", true);
        adminImplies.put("rec.admin.nolimit", true);
        adminImplies.put("rec.admin.nocost", true);
        adminImplies.put("rec.admin.zonebypass", true);
        permissions.add(new Permission("rec.admin",
                "Full administrative access to every claim and setting",
                PermissionDefault.OP, adminImplies));

        permissions.add(new Permission("rec.bypass",
                "Allows toggling the protection bypass with /claimadmin bypass", PermissionDefault.OP));
        permissions.add(new Permission("rec.admin.nolimit",
                "Exempt from the claim count limit", PermissionDefault.OP));
        permissions.add(new Permission("rec.admin.nocost",
                "Exempt from claim and fence material costs", PermissionDefault.OP));
        permissions.add(new Permission("rec.admin.zonebypass",
                "May create claims inside no-claim zones", PermissionDefault.OP));

        // Not for operators, and not a child of rec.admin. See the class note: an admin should pay a
        // toll like anybody else unless somebody deliberately says otherwise.
        permissions.add(new Permission("rec.admin.nofee",
                "Exempt from claim entry fees", PermissionDefault.FALSE));
        permissions.add(new Permission("rec.maxclaims.unlimited",
                "Removes the claim count cap entirely", PermissionDefault.FALSE));

        return permissions;
    }

    /**
     * Registers the lot.
     *
     * <p>Idempotent: one already registered — by a reload, or by a host that shades two copies — is left
     * as it is rather than throwing, because a module that refuses to start over a duplicate permission
     * is worse than one that shares.
     *
     * @return how many were newly registered
     */
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
                // Registered between the check and the add. It exists, which is all this wanted.
            }
        }
        return added;
    }
}
