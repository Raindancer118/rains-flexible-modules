package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /demote <player>} — takes somebody down a rung, or off the staff entirely.
 *
 * <h2>Why one step rather than straight off</h2>
 * Because "demote" usually means "not this rank any more", not "not staff any more" — an admin who
 * misused the config is often still a perfectly good moderator. So the default is one rung down, and
 * {@code /demote <player> none} is the way to say the harder thing out loud.
 *
 * <p>Ops only, for the same reason as {@code /promote}: a power that hands out powers cannot be one of
 * the powers it hands out.
 */
public final class DemoteCommand implements IModerationCommand {

    /** The word that means "off the staff", rather than one rung down. */
    private static final String OFF_ENTIRELY = "none";

    private final Supplier<ModerationServices> services;

    public DemoteCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "takes somebody down a rank, or off the staff";
    }

    @Override
    public String permission() {
        return PromoteCommand.USE;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.isOp() || sender.hasPermission(PromoteCommand.USE);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services.get();

        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage",
                    "usage", "/demote <player> [" + OFF_ENTIRELY + "]");
            return;
        }
        Optional<OfflinePlayer> found = Players.find(moderation.server(), args[0]);
        if (found.isEmpty()) {
            moderation.messages().send(sender, "moderation.no-such-player", "player", args[0]);
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);

        Optional<StaffRank> current = moderation.roster().rankOf(them.getUniqueId());
        if (current.isEmpty()) {
            moderation.messages().send(sender, "moderation.rank.not-staff", "player", name);
            return;
        }
        boolean offEntirely = args.length > 1
                && args[1].toLowerCase(java.util.Locale.ROOT).startsWith("n");

        if (offEntirely || current.get().ordinal() == 0) {
            // Already on the bottom rung: down from there is off, which is the only thing left to mean.
            moderation.staff().demote(sender, them.getUniqueId(), name);
            return;
        }
        moderation.staff().promote(sender, them.getUniqueId(), name,
                StaffRank.values()[current.get().ordinal() - 1]);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services.get().server(), args.length == 1 ? args[0] : "");
        }
        return args.length == 2 ? List.of(OFF_ENTIRELY) : List.of();
    }
}
