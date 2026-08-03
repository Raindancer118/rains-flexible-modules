package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /warn} — on the record, and nothing else.
 *
 * <h2>Why a warning is worth a command</h2>
 * Because most of moderation is telling somebody to stop, and almost everybody does. A warning that is
 * only ever said in chat leaves nothing behind, so the fourth one looks like the first — to the next
 * moderator, and in every appeal that follows.
 *
 * <p>It stops nothing. Deliberately: a "warning" that also silenced somebody would be a mute under
 * another name, and a record that could not tell the two apart.
 */
public final class WarnCommand extends StaffCommand {

    public WarnCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.WARN);
    }

    @Override
    public String describe() {
        return "puts a warning on somebody's record, and tells them why";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length < 2) {
            moderation.messages().send(sender, "moderation.usage", "usage", "/warn <player> <reason>");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        if (!mayAct(sender, them.getUniqueId())) {
            return;
        }
        // A reason is required rather than defaulted. "no reason given" on a warning is a line in a
        // record that helps nobody and cannot be answered later.
        moderation.punishmentService().punish(actorOf(sender), actorNameOf(sender),
                them.getUniqueId(), Players.nameOf(them), PunishmentKind.WARNING,
                Sentence.forEver(), reasonFrom(args, 1));
        moderation.messages().send(sender, "moderation.punished",
                "player", Players.nameOf(them), "what", PunishmentKind.WARNING.past(),
                "length", "once");
    }
}
