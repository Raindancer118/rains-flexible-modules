package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * {@code /speedrunspectate} — toggles the sender's own "not racing" status. See
 * {@link SpeedrunLobby#toggleSpectator} for why this is sticky rather than a per-run choice.
 */
public final class SpeedrunSpectateCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunSpectateCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "speedrun.spectate.only-a-player");
            return;
        }
        boolean nowSpectating = live.lobby().toggleSpectator(player.getUniqueId());
        live.messages().send(sender, nowSpectating ? "speedrun.spectate.on" : "speedrun.spectate.off");
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.SPECTATE;
    }

    @Override
    public String describe() {
        return "toggle not racing the next speedrun";
    }
}
