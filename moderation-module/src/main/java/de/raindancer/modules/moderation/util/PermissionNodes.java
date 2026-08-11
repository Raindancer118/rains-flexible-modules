package de.raindancer.modules.moderation.util;

import de.raindancer.modules.moderation.command.PromoteCommand;
import de.raindancer.modules.moderation.command.ReportCommand;
import de.raindancer.modules.moderation.listener.SuspiciousCommandListener;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.rules.StaffRule;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * Telling the server which permissions exist, and who has them without being granted anything.
 *
 * <h2>Why this is not optional</h2>
 * An unregistered permission resolves against {@code Permission.DEFAULT_PERMISSION}, which is
 * {@code OP} — so {@code /report} refused every ordinary player, on a server where the only person who
 * could test it was the one person for whom it worked.
 *
 * <p>And the correction has its own trap, which this walked straight into: registering the staff nodes as
 * {@link PermissionDefault#FALSE} took ten commands away from the <em>owner</em>, because {@code FALSE}
 * means nobody rather than "not by default". Both mistakes are silent, and both are why the defaults are
 * now asserted in a test rather than chosen in passing.
 *
 * <p>So the defaults are declared here rather than in a {@code paper-plugin.yml}. A module does not have
 * one of its own — it may be hosted inside somebody else's plugin — and registering programmatically is
 * the only way that works in both arrangements.
 *
 * <h2>Why registering also matters for the nodes nobody gets by default</h2>
 * A registered permission appears in {@code /help}, in permission plugins' tab completion, and in
 * LuckPerms' editor. An unregistered one is a string somebody has to already know about, which is how a
 * server ends up granting {@code rains.moderation.bans} — a node that does not exist and never warns
 * anybody.
 */
public final class PermissionNodes {

    private PermissionNodes() {
    }

    /**
     * Every node this module understands, with its default.
     *
     * <p>Built rather than registered, so the defaults can be asserted without a server. That split is
     * not decoration: the first version of this registered every staff node as
     * {@link PermissionDefault#FALSE}, which does not mean "not by default" — it means <b>nobody, the
     * server owner included</b>. Ten commands stopped working for the one person who could test them,
     * and nothing said so. {@code PermissionDefaultsTest} now asserts that no node a command gates on
     * can ever be {@code FALSE} again.
     *
     * <pre>
     *   TRUE    → everybody
     *   OP      → operators
     *   NOT_OP  → everybody except operators
     *   FALSE   → nobody, operators included
     * </pre>
     */
    public static List<Permission> declared() {
        List<Permission> wanted = new ArrayList<>();

        // Every staff node: operators, and anybody granted a rank. OP rather than FALSE because the
        // owner is the top rank — a fresh server with one op should have every command working, not a
        // server whose owner has to promote themselves before /ban does anything.
        for (ModerationPermission permission : ModerationPermission.values()) {
            wanted.add(new Permission(permission.node(), permission.describe(),
                    PermissionDefault.OP));
        }
        // There is deliberately no node for protection. It used to be one, and a permission is a fact
        // about somebody who is online — which the subject of a ban usually is not. It is now the
        // console-written list in ImmuneStaff, plus operators. Registering a node that no longer
        // confers anything would be worse than not having one: a server would grant it and believe
        // the account was protected.
        wanted.add(new Permission(PromoteCommand.USE,
                "Hand out and take away staff ranks. Deliberately not grantable by any rank",
                PermissionDefault.OP));

        // The one node every player has. TRUE rather than NOT_OP: a moderator is a player too, and
        // should be able to report somebody rather than being told they may not.
        wanted.add(new Permission(ReportCommand.USE,
                "File a report about another player", PermissionDefault.TRUE));

        // OP, not a rank preset: exempting staff from being auto-reported for testing the feature, or
        // for typing /seed to help somebody, is not a working power the way MUTE or KICK are.
        wanted.add(new Permission(SuspiciousCommandListener.BYPASS,
                "Exempt from automatic flagging: suspicious commands and the x-ray watch",
                PermissionDefault.OP));

        return wanted;
    }

    /**
     * Registers the lot.
     *
     * <p>Idempotent: a node already registered — by a reload, or by a host that shades two copies — is
     * left as it is rather than throwing, because a module that refuses to start over a duplicate
     * permission is worse than one that shares.
     *
     * @return how many were newly registered
     */
    public static int register(Server server) {
        if (server == null) {
            return 0;
        }
        List<Permission> wanted = declared();

        int added = 0;
        for (Permission permission : wanted) {
            if (server.getPluginManager().getPermission(permission.getName()) != null) {
                continue;
            }
            try {
                server.getPluginManager().addPermission(permission);
                added++;
            } catch (IllegalArgumentException alreadyThere) {
                // Registered between the check and the add, or by another copy of this module. Either
                // way it exists, which is all this method wanted.
            }
        }
        return added;
    }

    /**
     * Every node this module knows about, for a diagnostic.
     *
     * <p>Includes the other modules' nodes the presets grant — claims, warps, homes, teleports and the
     * Hunger Games gamemaster — which this module does not own. They are listed so that a page saying
     * "here is everything a rank can give you" is complete, and deliberately not <em>registered</em> here,
     * because inventing a default for another module's permission is how two plugins come to disagree
     * about what it means.
     *
     * <p>Taken from {@link StaffRank#everyGrantableNode()} rather than named again. The version that
     * listed the foreign nodes by hand had drifted: it knew about the two claims ones and had never heard
     * of the staff warps, the teleport bypasses or unlimited homes, all of which a rank had been granting
     * for months. A list that is written twice is one that is right once.
     */
    public static List<String> all() {
        List<String> nodes = new ArrayList<>();
        for (ModerationPermission permission : ModerationPermission.values()) {
            nodes.add(permission.node());
        }
        nodes.add(PromoteCommand.USE);
        nodes.add(ReportCommand.USE);
        nodes.add(SuspiciousCommandListener.BYPASS);
        for (String granted : StaffRank.everyGrantableNode()) {
            if (!nodes.contains(granted)) {
                nodes.add(granted);
            }
        }
        return List.copyOf(nodes);
    }
}
