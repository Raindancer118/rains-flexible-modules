package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /staffchat} — say one line to the staff, or stay in the channel.
 *
 * <h2>The two shapes, and why both</h2>
 * {@code /staffchat <message>} sends one line and leaves the toggle alone: nothing to remember, nothing
 * to forget. Bare {@code /staffchat} toggles, for the ten minutes in which every other line is staff
 * business.
 *
 * <p>The toggle is deliberately not a chat prefix. Somebody who forgets a {@code #} has said to the
 * whole server what they meant to say to two people; somebody who forgets a toggle says nothing to
 * anybody, notices, and says it again. The failures are not the same size.
 */
public final class StaffChatCommand extends StaffCommand {

    public StaffChatCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.STAFF_CHAT);
    }

    @Override
    public String describe() {
        return "talks to the staff rather than to the server";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length > 0) {
            moderation.staffChatListener().say(sender.getName(), String.join(" ", args));
            return;
        }
        if (!(sender instanceof Player player)) {
            // The console cannot be "in" the channel — it already sees every line of it.
            moderation.messages().send(sender, "moderation.usage", "usage", "/staffchat <message>");
            return;
        }
        boolean nowTalking = moderation.staffChat().toggle(player.getUniqueId());
        moderation.messages().send(player,
                nowTalking ? "moderation.staff-chat.on" : "moderation.staff-chat.off");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();   // it takes a sentence, and completing one is noise
    }
}
