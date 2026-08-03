package de.raindancer.modules.moderation.command;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /report} — the one command in this module a player runs.
 *
 * <h2>Why it does not use {@code StaffCommand}</h2>
 * Because everything that class does is about staff: a moderation permission, an immunity check, a
 * refusal that names a node. This one is for everybody, and the only thing it may not do is let a player
 * discover who is immune by watching which answer they get. So it says the same thing either way and
 * asks {@code ReportRule}, which is the only judge here.
 *
 * <p>Its permission node exists so that a server can take it away from somebody who abuses it, which is
 * the alternative to switching reports off for everybody.
 */
public final class ReportCommand implements IModerationCommand {

    /** Held rather than granted: everybody has it by default, and it is taken away individually. */
    public static final String USE = ModerationPermission.PREFIX + "report";

    private final Supplier<ModerationServices> services;

    public ReportCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "tells the staff about somebody, and tells you what they decided";
    }

    @Override
    public String permission() {
        return USE;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(USE);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services.get();

        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage",
                    "usage", "/report <player> [what happened]");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(moderation.server(), args[0]);
        if (found.isEmpty()) {
            moderation.messages().send(sender, "moderation.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();

        // Named somebody and nothing else: offer the categories rather than a usage line. Typing the
        // whole thing still works and is the faster path for anybody who knows what to say.
        if (args.length == 1) {
            if (sender instanceof Player viewer) {
                moderation.screens().reportCategories(viewer, them.getUniqueId(),
                        Players.nameOf(them));
            } else {
                moderation.messages().send(sender, "moderation.usage",
                        "usage", "/report <player> <what happened>");
            }
            return;
        }
        String what = String.join(" ", java.util.List.of(args).subList(1, args.length)).trim();
        UUID reporter = sender instanceof Player player ? player.getUniqueId() : null;

        Verdict allowed = moderation.reportService().mayFile(reporter, them.getUniqueId(), what);
        if (allowed.isRefused()) {
            allowed.refusal().ifPresent(reason -> moderation.messages().send(sender, reason,
                    "detail", allowed.detail() == null ? "" : allowed.detail()));
            return;
        }
        moderation.reportService().file(reporter, sender.getName(), them.getUniqueId(),
                        Players.nameOf(them), what)
                .ifPresentOrElse(
                        filed -> moderation.messages().send(sender, "moderation.report.filed",
                                "id", filed.id(), "player", Players.nameOf(them)),
                        // Between the check and the write somebody else's report about the same
                        // person can arrive. Saying so beats a command that quietly does nothing.
                        () -> moderation.messages().send(sender, "moderation.report.not-taken"));
    }

    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
        }
        return java.util.List.of();
    }
}
