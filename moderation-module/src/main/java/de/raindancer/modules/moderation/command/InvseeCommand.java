package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.invsee.Access;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /invsee} — look inside somebody's inventory.
 *
 * <h2>Almost none of this is here either</h2>
 * Reading a logged-out player means reading and un-gzipping their data file, claiming it so that they
 * cannot log in on top of the edit, writing it back, and giving up the claim if they do come back. That
 * is {@code core.moderation.invsee}, and it is a great deal of careful code that no module should own a
 * second copy of. This command names a player and picks an access level.
 *
 * <h2>Why editing is a separate permission</h2>
 * Because looking and taking are different powers, and a server that wants a helper who can check
 * whether somebody has a stack of spawners should not thereby be handing them everybody's inventory.
 * Armour needs the wider grant again, so that taking somebody's helmet off by clicking one slot too far
 * is not something a look-only moderator can do at all.
 */
public final class InvseeCommand extends StaffCommand {

    public InvseeCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.INVSEE);
    }

    @Override
    public String describe() {
        return "opens somebody's inventory, online or not";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (!(sender instanceof Player watcher)) {
            moderation.messages().send(sender, "moderation.only-a-player");
            return;
        }
        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage", "usage", "/invsee <player>");
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
        Access wanted = watcher.hasPermission(ModerationPermission.INVSEE_EDIT.node())
                ? Access.EDIT : Access.READ_ONLY;

        moderation.inventories().open(watcher, them.getUniqueId(), Players.nameOf(them), wanted,
                outcome -> {
                    // Always answered, and always on this thread. A command that opens nothing and
                    // says nothing is one somebody types four more times.
                    if (!outcome.opened()) {
                        moderation.messages().send(watcher, "moderation.invsee.refused",
                                "reason", outcome.saying());
                    }
                });
    }
}
