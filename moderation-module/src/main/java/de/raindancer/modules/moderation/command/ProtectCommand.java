package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /protect} and {@code /unprotect} — the only way an account becomes untouchable.
 *
 * <h2>Why the console and nothing else</h2>
 * Protection is what stops one moderator acting on another. Anything in the game that could hand it out
 * — a rank, a menu, a permission granted in LuckPerms — is a shield the people it is aimed at can pass
 * to each other, and a compromised admin account is one click from being unbannable by everybody except
 * whoever can reach the console. So the console is the only door, and standing at it is the whole
 * qualification.
 *
 * <p>That is also why this does not extend {@link StaffCommand}: there is no permission node behind it
 * to hold. {@link #canUse} answers false for every player, so it does not appear in their tab
 * completion either — a command somebody can see and never use is one they will ask about.
 *
 * <h2>What it is not</h2>
 * Not a punishment and not a rank. It changes nothing about what somebody may <em>do</em>; it changes
 * only what may be done <em>to</em> them, and only by moderators — the console still acts on a protected
 * account, which is the case it exists to leave open.
 *
 * <p>Operators are protected without being on this list at all, so a fresh server is never in the window
 * where an admin can ban the owner before anybody has typed anything. Which also means
 * {@code /unprotect} on an operator says so rather than pretending to have done something.
 */
public final class ProtectCommand implements IModerationCommand {

    private final Supplier<ModerationServices> services;

    /** True for {@code /protect}, false for {@code /unprotect}. One class: they are one decision. */
    private final boolean protecting;

    public ProtectCommand(Supplier<ModerationServices> services, boolean protecting) {
        this.services = services;
        this.protecting = protecting;
    }

    @Override
    public String describe() {
        return protecting
                ? "protects an account from moderators — console only"
                : "takes that protection off again — console only";
    }

    @Override
    public boolean consoleOnly() {
        return true;
    }

    /**
     * Console only, and asked twice on purpose.
     *
     * <p>Here it decides whether the command is offered at all; {@link #execute} decides whether it
     * runs. Paper does not promise that a handler is never reached for somebody {@code canUse} said no
     * to — command blocks, other plugins dispatching, a future change to the API — and this is not a
     * check worth making only once.
     */
    @Override
    public boolean canUse(CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services.get();

        if (!(sender instanceof ConsoleCommandSender)) {
            moderation.messages().send(sender, "moderation.protect.console-only");
            return;
        }

        if (args.length == 0) {
            // A bare command lists rather than printing usage. It is the question somebody at the
            // console actually has — "who is protected?" — and it has nowhere else to be asked.
            list(moderation, sender);
            return;
        }

        Optional<OfflinePlayer> found = Players.find(moderation.server(), args[0]);
        if (found.isEmpty()) {
            // Never a made-up profile: protecting a typo writes an id nobody holds, and the account
            // somebody believes is protected is not.
            moderation.messages().send(sender, "moderation.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);
        UUID who = them.getUniqueId();

        if (protecting) {
            protect(moderation, sender, who, name);
        } else {
            unprotect(moderation, sender, who, name, them.isOp());
        }
    }

    private void protect(ModerationServices moderation, CommandSender sender, UUID who, String name) {
        if (!moderation.protectedAccounts().protect(who)) {
            moderation.messages().send(sender, "moderation.protect.already", "player", name);
            return;
        }
        if (!write(moderation, sender)) {
            return;
        }
        moderation.messages().send(sender, "moderation.protect.protected", "player", name);
        moderation.audit().record(AuditEntry.of("moderation", "protect")
                .by(null, "the console")
                .to(who, name)
                .saying("protected from moderators"));
    }

    private void unprotect(ModerationServices moderation, CommandSender sender, UUID who, String name,
                           boolean operator) {
        if (!moderation.protectedAccounts().unprotect(who)) {
            // Says which of the two "no" answers this is. An operator is protected without being on
            // the list, and "not on the list" would read as "they can be banned now", which is wrong.
            moderation.messages().send(sender,
                    operator ? "moderation.protect.operator" : "moderation.protect.not-protected",
                    "player", name);
            return;
        }
        if (!write(moderation, sender)) {
            return;
        }
        moderation.messages().send(sender, "moderation.protect.unprotected", "player", name);
        moderation.audit().record(AuditEntry.of("moderation", "unprotect")
                .by(null, "the console")
                .to(who, name)
                .saying("no longer protected from moderators"));
    }

    /**
     * Writes the list, now, and says so if it did not reach the disk.
     *
     * <p>On the calling thread rather than scheduled: the console's next line has to be true, and a
     * change that is announced and then lost at the next restart is the one failure this list cannot
     * have. It is a handful of ids in one small file.
     */
    private boolean write(ModerationServices moderation, CommandSender sender) {
        if (moderation.protectedAccounts().flush()) {
            return true;
        }
        moderation.messages().send(sender, "moderation.protect.not-written");
        return false;
    }

    private void list(ModerationServices moderation, CommandSender sender) {
        List<UUID> everybody = new ArrayList<>(moderation.protectedAccounts().all());
        if (everybody.isEmpty()) {
            // Not an empty list: operators are protected too, and a console reading "nobody" would
            // reasonably conclude the owner is bannable.
            moderation.messages().send(sender, "moderation.protect.none");
            return;
        }
        moderation.messages().send(sender, "moderation.protect.list", "count", everybody.size());
        for (UUID who : everybody) {
            moderation.messages().sendPlain(sender, "moderation.protect.row",
                    "player", Players.nameOf(moderation.server(), who));
        }
    }

    /** Names, for the console's tab completion. Nobody else ever gets this far. */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof ConsoleCommandSender) || args.length > 1) {
            return List.of();
        }
        return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
    }
}
