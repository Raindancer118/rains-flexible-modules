package de.raindancer.modules.moderation.command;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What every moderation command needs, once.
 *
 * <h2>Why a base class rather than a utility</h2>
 * Because the same five steps happen at the top of every one of these — is the module running, who is
 * asking, who did they mean, may they, and say so if not — and the version of this plugin that had them
 * copied out in five commands had them drift: one command resolved offline players and another did not,
 * so {@code /mute} worked on somebody who had just logged off and {@code /ban} did not.
 *
 * <p>Nothing here captures anything. The services arrive through a supplier, asked at the moment the
 * command runs, because these objects exist before the module does.
 */
public abstract class StaffCommand implements IModerationCommand {

    private final Supplier<ModerationServices> services;
    private final ModerationPermission permission;

    protected StaffCommand(Supplier<ModerationServices> services, ModerationPermission permission) {
        this.services = services;
        this.permission = permission;
    }

    /** The services, or an exception naming the real problem. Guarded by the host, so unreachable. */
    protected ModerationServices services() {
        return services.get();
    }

    protected ModerationPermission permissionNeeded() {
        return permission;
    }

    @Override
    public String permission() {
        return permission.node();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(permission.node());
    }

    /** Whoever typed it, as an id — null for the console, which every rule here reads as "may". */
    protected static UUID actorOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** How they are named in a message and in the audit line. */
    protected static String actorNameOf(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "the console";
    }

    /**
     * The player they meant, or empty with the reason already said.
     *
     * <p>Offline included — the whole point of a ban command is usually that they are not here — but a
     * name the server has never seen gives nothing back rather than a made-up profile, because banning
     * a typo is a ban nobody can lift.
     */
    protected Optional<OfflinePlayer> subject(CommandSender sender, String name) {
        Optional<OfflinePlayer> found = Players.find(services().server(), name);
        if (found.isEmpty()) {
            services().messages().send(sender, "moderation.no-such-player", "player", name);
        }
        return found;
    }

    /** Whether this actor may do this to this person, saying why not when they may not. */
    protected boolean mayAct(CommandSender sender, UUID subject) {
        Verdict verdict = services().staffRule().canAct(actorOf(sender), subject, permission);
        if (verdict.isAllowed()) {
            return true;
        }
        verdict.refusal().ifPresent(reason -> services().messages().send(sender, reason,
                "detail", verdict.detail() == null ? "" : verdict.detail()));
        return false;
    }

    /** Everything after the first n words, as one reason. */
    protected static String reasonFrom(String[] args, int from) {
        if (args == null || args.length <= from) {
            return "no reason given";
        }
        return String.join(" ", List.of(args).subList(from, args.length)).trim();
    }

    /** Names, online first. A four-year-old server has thousands, so the list is capped. */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services().server(), args.length == 1 ? args[0] : "");
        }
        return List.of();
    }
}
