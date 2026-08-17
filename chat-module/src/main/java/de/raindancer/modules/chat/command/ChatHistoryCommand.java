package de.raindancer.modules.chat.command;

import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.store.ChatHistoryStore;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /chathistory} — what you missed while you were away; {@code /chathistory <count>} — the
 * last {@code count} lines regardless of when you left.
 */
public final class ChatHistoryCommand implements IChatCommand {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_REQUESTED = 200;

    private final Supplier<ChatServices> services;

    public ChatHistoryCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "shows chat that happened while you were away";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "chat.only-a-player");
            return;
        }
        if (!live.history().historyEnabled()) {
            live.messages().send(who, "chat.history.disabled");
            return;
        }

        List<ChatHistoryStore.Line> lines;
        if (args.length == 0) {
            lines = live.history().missedBy(who.getUniqueId());
        } else {
            try {
                int count = Integer.parseInt(args[0]);
                lines = live.history().recent(Math.max(1, Math.min(MAX_REQUESTED, count)));
            } catch (NumberFormatException notANumber) {
                live.messages().send(who, "chat.usage", "usage", "/chathistory [count]");
                return;
            }
        }

        if (lines.isEmpty()) {
            live.messages().send(who, "chat.history.none");
            return;
        }
        live.messages().send(who, "chat.history.header", "count", String.valueOf(lines.size()));
        for (ChatHistoryStore.Line line : lines) {
            String time = Instant.ofEpochMilli(line.at()).atZone(ZoneId.systemDefault())
                    .format(CLOCK);
            live.messages().sendPlain(who, "chat.history.line", "time", time,
                    "player", line.senderName(), "text", line.text());
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length == 1 ? List.of("20", "50", "100") : List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.HISTORY;
    }
}
