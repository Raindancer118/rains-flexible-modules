package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /announce <message>} — a banner every online player sees and hears, for the handful of
 * things a server wants nobody to scroll past.
 *
 * <h2>Why this plays a cue rather than choosing a sound</h2>
 * {@link Cues#NOTIFY} is already Core's name for exactly this — "a message that should not be
 * scrolled past" — bound once for every plugin on the server. Picking a sound here would be a second,
 * competing idea of what an announcement sounds like the moment another plugin also has one.
 */
public final class AnnounceCommand implements IChatCommand {

    private final Supplier<ChatServices> services;

    public AnnounceCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "broadcasts a banner every online player sees and hears";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            live.messages().send(sender, "chat.usage", "usage", "/announce <message>");
            return;
        }
        String text = String.join(" ", args);

        live.chat().broadcast(live.messages().raw("chat.announce.banner"), Chat.arg("text", text));

        List<java.util.UUID> everybody = live.server().getOnlinePlayers().stream()
                .map(Player::getUniqueId).toList();
        live.core().effects().playForAll(everybody, Cues.NOTIFY);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.ANNOUNCE;
    }
}
