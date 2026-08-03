package de.raindancer.modules.moderation.util;

import de.raindancer.modules.moderation.command.PromoteCommand;
import de.raindancer.modules.moderation.command.ReportCommand;
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
 * Because <b>staff are not operators</b> — that is the whole design — and an unregistered permission is
 * one that {@code hasPermission} answers {@code false} to for everybody who is not op. Which is correct
 * for the staff nodes and catastrophic for {@code /report}: a command every player is supposed to be able
 * to run, refusing every player, on a server where the only person who can test it is the one person for
 * whom it works.
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
     * Registers every node this module understands, with its default.
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
        List<Permission> wanted = new ArrayList<>();

        // Every staff node: nobody by default. They arrive through a rank, never through op-ness, and
        // never through being on the server.
        for (ModerationPermission permission : ModerationPermission.values()) {
            wanted.add(new Permission(permission.node(), permission.describe(),
                    PermissionDefault.FALSE));
        }
        wanted.add(new Permission(StaffRule.IMMUNE,
                "Cannot be acted on by moderators — only from the console", PermissionDefault.FALSE));
        wanted.add(new Permission(PromoteCommand.USE,
                "Hand out and take away staff ranks. Deliberately not grantable by any rank",
                PermissionDefault.OP));

        // The one node every player has. TRUE rather than NOT_OP: a moderator is a player too, and
        // should be able to report somebody rather than being told they may not.
        wanted.add(new Permission(ReportCommand.USE,
                "File a report about another player", PermissionDefault.TRUE));

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
     * <p>Includes the claims nodes the presets grant, which this module does not own — they are listed
     * so that a page saying "here is everything a rank can give you" is complete, and deliberately not
     * <em>registered</em> here, because inventing a default for another module's permission is how two
     * plugins come to disagree about what it means.
     */
    public static List<String> all() {
        List<String> nodes = new ArrayList<>();
        for (ModerationPermission permission : ModerationPermission.values()) {
            nodes.add(permission.node());
        }
        nodes.add(StaffRule.IMMUNE);
        nodes.add(PromoteCommand.USE);
        nodes.add(ReportCommand.USE);
        nodes.add(StaffRank.CLAIM_ADMIN);
        nodes.addAll(StaffRank.CLAIM_BYPASSES);
        return List.copyOf(nodes);
    }
}
