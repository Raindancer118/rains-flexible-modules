package de.raindancer.modules.rtp.command;

import de.raindancer.modules.rtp.RtpServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /rtp} — send the player somewhere random in their own world.
 *
 * <p>Deliberately takes no arguments. There is nothing here a menu could ask for either, with one
 * exception: whether this trip's landing is checked for safety, when the owner's settings leave that
 * up to the player — see {@code RtpSettings#safeArrivalPolicy()}. Under any other policy there is
 * nothing to ask, so the trip goes straight ahead. Everything else that varies is an owner's setting,
 * reached through {@code /settings}.
 */
public final class RtpCommand implements IRtpCommand {

    private final Supplier<RtpServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IRtpCommand} on
     *                 why a command built at bootstrap cannot hold anything the module built
     */
    public RtpCommand(Supplier<RtpServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        RtpServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "rtp.only-a-player");
            return;
        }
        if (live.rtp().playerMayChoose()) {
            live.screens().chooser(player);
            return;
        }
        // Nothing to ask: the owner's policy has already decided one way or the other, so the safe
        // choice passed here is only ever a placeholder the rule ends up ignoring.
        live.rtp().go(player, true);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return de.raindancer.modules.rtp.util.PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "go somewhere random in your own world";
    }
}
