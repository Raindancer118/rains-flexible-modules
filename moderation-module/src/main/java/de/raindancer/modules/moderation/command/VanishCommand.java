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
 * {@code /vanish} — go invisible, or come back.
 *
 * <h2>What this class actually contains</h2>
 * Almost nothing, and that is the point. Vanishing is {@code core.moderation.vanish.Vanish}: hiding
 * somebody from everybody who may not see them, remembering it across a relog, suppressing the join and
 * quit messages, and putting flight back the way it was. All of that is Core's because a vanished
 * moderator has to be vanished from every plugin's point of view — a second implementation is somebody
 * invisible to the chat plugin and visible in the tab list.
 *
 * <p>What is here is the product decision: whether flight comes with it, and who may.
 */
public final class VanishCommand extends StaffCommand {

    public VanishCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.VANISH);
    }

    @Override
    public String describe() {
        return "makes you invisible to everybody who may not see vanished players";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (!(sender instanceof Player player)) {
            // The console has nobody to hide. Saying so beats a stack trace, and beats silence.
            moderation.messages().send(sender, "moderation.only-a-player");
            return;
        }
        moderation.vanish().flightWhileVanished(moderation.config().flightWhileVanished());

        boolean nowHidden = moderation.vanish().isVanished(player.getUniqueId())
                ? !moderation.vanish().reveal(player.getUniqueId())
                : moderation.vanish().vanish(player.getUniqueId(), player.getAllowFlight());

        moderation.messages().send(player,
                nowHidden ? "moderation.vanished" : "moderation.visible-again");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();   // it takes nothing
    }
}
