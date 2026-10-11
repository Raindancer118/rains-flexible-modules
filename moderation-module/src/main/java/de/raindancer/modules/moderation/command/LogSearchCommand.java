package de.raindancer.modules.moderation.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.LogQuery;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.LogSearchService;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A search staff type, answered in chat a page at a time: the newest first, every page and every name a click.
 *
 * <p>The database is read off the server's threads; the answer goes back on the sender's own. Over RCON, whose
 * reply is gone by the time the search is done, the answer is also written to the server console.
 *
 * @param <T> what a line of the answer is
 */
abstract class LogSearchCommand<T> extends StaffCommand {

    static final int PLAYER_PAGE = 10;
    static final int CONSOLE_PAGE = 20;

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
            .withZone(LogSearchService.ZONE);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(LogSearchService.ZONE);
    private static final List<String> KEYS = List.of("player:", "from:", "to:", "day:", "last:", "page:");

    protected LogSearchCommand(Supplier<ModerationServices> services, ModerationPermission permission) {
        super(services, permission);
    }

    /** The command's own name, for the clicks. */
    protected abstract String label();

    /** The message keys under {@code moderation.<prefix>.}: heading, line. */
    protected abstract String prefix();

    /** Blocking; called off the server's threads. */
    protected abstract LogSearchService.Page<T> search(LogQuery query, int pageSize);

    /** One line of the answer. */
    protected abstract Component line(T item, LogQuery query);

    /** One line of the answer for this reader; the same for everybody unless a command says otherwise. */
    protected Component line(T item, LogQuery query, CommandSender reader) {
        return line(item, query);
    }

    /** Parts of the query this command does not understand, as typed; empty when it is fine. */
    protected List<String> refused(LogQuery query) {
        return List.of();
    }

    /** Keys offered to complete besides the shared ones. */
    protected List<String> moreKeys() {
        return List.of();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(source.getSender(), args);
    }

    protected void run(CommandSender sender, String[] args) {
        ModerationServices moderation = services();
        LogQuery query = LogQuery.parse(args, LogSearchService.ZONE, Instant.now());
        if (!query.problems().isEmpty()) {
            moderation.messages().send(sender, "moderation.logsearch.unreadable", "part", query.problems().getFirst());
            moderation.messages().send(sender, "moderation." + prefix() + ".help");
            return;
        }
        List<String> refused = refused(query);
        if (!refused.isEmpty()) {
            moderation.messages().send(sender, "moderation.logsearch.unknown-type", "part", refused.getFirst());
            return;
        }
        int size = sender instanceof Player ? PLAYER_PAGE : CONSOLE_PAGE;
        Scheduling.async(moderation.plugin(), () -> {
            LogSearchService.Page<T> page = search(query, size);
            Runnable answer = () -> answer(sender, query, page);
            if (sender instanceof Player player) {
                Scheduling.entity(moderation.plugin(), player, answer);
            } else {
                Scheduling.global(moderation.plugin(), answer);
            }
        });
    }

    private void answer(CommandSender sender, LogQuery query, LogSearchService.Page<T> page) {
        List<CommandSender> to = new ArrayList<>(List.of(sender));
        if (sender instanceof RemoteConsoleCommandSender) {
            to.add(services().server().getConsoleSender());
        }
        for (CommandSender reader : to) {
            answerOne(reader, query, page);
        }
    }

    private void answerOne(CommandSender sender, LogQuery query, LogSearchService.Page<T> page) {
        ModerationServices moderation = services();
        if (!page.nobody().isEmpty()) {
            moderation.messages().send(sender, "moderation.logsearch.nobody", "player", String.join(", ", page.nobody()));
            return;
        }
        String what = query.describe(LogSearchService.ZONE, this::shownName);
        if (page.total() == 0) {
            moderation.messages().send(sender, "moderation.logsearch.none", "what", what);
            return;
        }
        Chat chat = moderation.chat();
        chat.tell(sender, moderation.messages().raw("moderation." + prefix() + ".heading"),
                Chat.arg("what", what), Chat.arg("count", page.total()),
                Chat.arg("page", page.page()), Chat.arg("pages", page.pages()));
        for (T item : page.items()) {
            chat.raw(sender, line(item, query, sender));
        }
        if (page.pages() > 1) {
            chat.row(sender, moderation.messages().raw("moderation.logsearch.pages"),
                    Chat.formatted("newer", pageButton(query, page.page() - 1, page.pages(), "newer")),
                    Chat.formatted("older", pageButton(query, page.page() + 1, page.pages(), "older")),
                    Chat.arg("page", page.page()), Chat.arg("pages", page.pages()));
        }
    }

    private Component pageButton(LogQuery query, int target, int pages, String which) {
        ModerationServices moderation = services();
        if (target < 1 || target > pages) {
            return moderation.messages().get("moderation.logsearch.no-" + which);
        }
        return moderation.messages().get("moderation.logsearch." + which)
                .hoverEvent(HoverEvent.showText(moderation.messages().get("moderation.logsearch.page-hover",
                        "page", target)))
                .clickEvent(ClickEvent.runCommand(command(query.atPage(target))));
    }

    /** The command that runs this search. */
    protected String command(LogQuery query) {
        String arguments = query.arguments(LogSearchService.ZONE);
        return "/" + label() + (arguments.isEmpty() ? "" : " " + arguments);
    }

    /** When something happened, short, with the full Berlin time on hover. */
    protected Component time(long at, String extra) {
        Instant moment = Instant.ofEpochMilli(at);
        Component hover = services().messages().get("moderation.logsearch.time-hover", "when", LONG.format(moment),
                "extra", extra == null ? "" : extra);
        return Component.text(SHORT.format(moment)).color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)
                .hoverEvent(HoverEvent.showText(hover));
    }

    /** A name that narrows the search to its player when clicked, the rest of the search kept. */
    protected Component name(String name, UUID player, LogQuery query) {
        return Component.text(name).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(services().messages().get("moderation.logsearch.name-hover",
                        "player", name)))
                .clickEvent(ClickEvent.runCommand(command(query.onlyPlayer(player.toString()).atPage(1))));
    }

    private String shownName(String typed) {
        try {
            return Players.nameOf(services().server(), UUID.fromString(typed));
        } catch (IllegalArgumentException notAnId) {
            return typed;
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1];
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> offered = new ArrayList<>();
        if (lower.startsWith("player:") || lower.startsWith("p:")) {
            String prefix = typed.substring(0, typed.indexOf(':') + 1);
            for (String name : Players.suggestions(services().server(), typed.substring(prefix.length()))) {
                offered.add(prefix + name);
            }
            return offered;
        }
        if (lower.startsWith("day:")) {
            return List.of("day:today", "day:yesterday");
        }
        if (lower.startsWith("last:")) {
            return List.of("last:30m", "last:2h", "last:1d", "last:7d");
        }
        List<String> keys = new ArrayList<>(KEYS);
        keys.addAll(moreKeys());
        for (String key : keys) {
            if (key.startsWith(lower)) {
                offered.add(key);
            }
        }
        return offered;
    }
}
