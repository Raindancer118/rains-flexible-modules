package de.raindancer.modules.tpa.command;

import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /tpaccept} and {@code /tpdeny} — answering somebody.
 *
 * <p>One class for both, for the same reason as the asking: they differ by one word and share every
 * bit of the argument handling. With no name they answer the newest, which is the one that just
 * appeared on screen and therefore the one being replied to.
 */
public final class AnswerCommand implements ITpaCommand {

    private final Supplier<TpaServices> services;
    private final boolean accepting;

    public AnswerCommand(Supplier<TpaServices> services, boolean accepting) {
        this.services = services;
        this.accepting = accepting;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        TpaServices live = services.get();
        CommandSender sender = source.getSender();

        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "tpa.only-a-player");
            return;
        }
        UUID from = null;
        if (args.length > 0) {
            from = whoAsked(live, player, args[0]);
            if (from == null) {
                live.messages().send(player, "tpa.not-asked-by", "player", args[0]);
                return;
            }
        }
        if (accepting) {
            live.asking().accept(player, from);
        } else {
            live.asking().deny(player, from);
        }
    }

    /**
     * Which of the people waiting on them is called that.
     *
     * <p>Matched among the requests rather than among the players online, so a name is only ever
     * resolved against somebody who actually asked — and it works for somebody who has since walked
     * out of range of being found by name.
     */
    private static UUID whoAsked(TpaServices live, Player answering, String name) {
        return live.requests().to(answering.getUniqueId()).stream()
                .filter(request -> live.prefs().nameOf(request.from()).equalsIgnoreCase(name))
                .map(TpaRequest::from)
                .findFirst()
                .orElse(null);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (!(source.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        TpaServices live = services.get();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // Only the people actually waiting on them. Completing every player online would suggest
        // names that cannot be answered.
        return live.requests().to(player.getUniqueId()).stream()
                .map(request -> live.prefs().nameOf(request.from()))
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return accepting ? "accept a teleport request" : "turn a teleport request down";
    }
}
