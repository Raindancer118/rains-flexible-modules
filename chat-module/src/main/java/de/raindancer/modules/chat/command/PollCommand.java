package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.rules.PollRule;
import de.raindancer.modules.chat.screen.PollMenu;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /poll} — the builder menu; {@code /poll [length] question | answer | answer} to start one in
 * a line; {@code /poll results} and {@code /poll end}.
 */
public final class PollCommand implements IChatCommand {

    private final Supplier<ChatServices> services;

    public PollCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        String typed = String.join(" ", args).strip();
        if (typed.equalsIgnoreCase("results")) {
            live.polls().showResults(sender);
            return;
        }
        if (typed.equalsIgnoreCase("end")) {
            live.polls().endEarly(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "chat.only-a-player");
            return;
        }
        if (typed.isEmpty()) {
            new PollMenu(live, player, null).open();
            return;
        }
        Parsed<PollRule.Request> read = live.polls().rule().read(typed, live.polls().standardLength());
        if (!read.isOk()) {
            live.messages().send(player, "chat.poll.not-a-poll", "detail", read.problem());
            return;
        }
        live.polls().start(player, read.value().question(), read.value().answers(), read.value().lasting());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typing = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            return List.of("results", "end", "2m", "5m").stream().filter(word -> word.startsWith(typing)).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.POLL;
    }

    @Override
    public String describe() {
        return "starts a poll in chat, shows its results or ends it";
    }
}
