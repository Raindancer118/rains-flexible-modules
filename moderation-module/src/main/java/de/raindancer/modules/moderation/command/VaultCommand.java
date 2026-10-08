package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /vault} — opens the operator's own vault. Never anybody else's.
 *
 * <p>Operators only, like {@code /promote}: the node is in no rank preset, because a private stash is not
 * a working power a promotion should hand out.
 */
public final class VaultCommand implements IModerationCommand {

    public static final String USE = "rains.moderation.vault";

    private final Supplier<ModerationServices> services;

    public VaultCommand(Supplier<ModerationServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "opens your personal vault";
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
        ModerationServices moderation = services.get();
        if (!(source.getSender() instanceof Player owner)) {
            moderation.messages().send(source.getSender(), "moderation.only-a-player");
            return;
        }
        moderation.screens().vault(owner);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }
}
