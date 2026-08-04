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
 * {@code /kick} — off the server, once.
 *
 * <h2>Why it is a punishment at all</h2>
 * Because it goes on the record. A kick stops nothing — they may come straight back — but "we have
 * asked this person to stop four times this week" is exactly the kind of thing that is otherwise in one
 * moderator's memory and nowhere else. Recording it is the difference between a fifth kick and a first
 * ban that looks arbitrary.
 *
 * <h2>Its own class rather than another {@code PunishCommand} kind</h2>
 * Because a kick takes no length and never should. Folded in, the length-parsing branch would apply to
 * it, and {@code /kick somebody 2h} would silently swallow the {@code 2h} — which is a reason a moderator
 * typed and a player never sees.
 */
public final class KickCommand extends StaffCommand {

    public KickCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.KICK);
    }

    @Override
    public String describe() {
        return "throws somebody off the server, once, and records that it happened";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length == 0) {
            // A bare command opens the screen for it rather than reciting a syntax. Somebody who
            // typed "/kick" has already said what they want to do; answering with the grammar they
            // plainly do not have to hand is the least useful reply available. The console still gets
            // the usage line, having no screen to open.
            if (sender instanceof org.bukkit.entity.Player staff) {
                new de.raindancer.modules.moderation.screen.PlayerPickerMenu(moderation, staff, null,
                        (who, name) -> new de.raindancer.modules.moderation.screen.PunishMenu(moderation, staff, null, who, name,
                                de.raindancer.core.moderation.punishment.PunishmentKind.KICK)
                                .open()).open();
                return;
            }
            moderation.messages().send(sender, "moderation.usage", "usage", "/kick <player> [reason]");
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
        if (!them.isOnline()) {
            // Saying so beats recording a kick that did nothing and leaving somebody to wonder why
            // the player is still in the tab list.
            moderation.messages().send(sender, "moderation.not-here", "player", Players.nameOf(them));
            return;
        }
        moderation.punishmentService().punish(actorOf(sender), actorNameOf(sender),
                them.getUniqueId(), Players.nameOf(them), PunishmentKind.KICK, Sentence.forEver(),
                reasonFrom(args, 1));
        moderation.messages().send(sender, "moderation.punished",
                "player", Players.nameOf(them), "what", PunishmentKind.KICK.past(), "length", "once");
    }
}
