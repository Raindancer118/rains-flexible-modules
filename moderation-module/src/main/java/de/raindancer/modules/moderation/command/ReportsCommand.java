package de.raindancer.modules.moderation.command;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /reports} — the queue, as a page or as lines.
 *
 * <h2>Why both</h2>
 * A moderator sitting at the console has no screen to open, and a moderator halfway through something
 * else wants to know how many are waiting without a window covering the world. Bare {@code /reports}
 * opens the page for a player and lists them for the console; {@code /reports list} always lists.
 */
public final class ReportsCommand extends StaffCommand {

    /** How many lines is an answer rather than a wall of them. */
    private static final int MOST_LINES = 10;

    public ReportsCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.REPORTS);
    }

    @Override
    public String describe() {
        return "the report queue: what is waiting, and who has picked what up";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        boolean asLines = args.length > 0 && args[0].toLowerCase(Locale.ROOT).startsWith("l");
        if (sender instanceof Player player && !asLines) {
            moderation.screens().reports(player);
            return;
        }
        List<Report> live = moderation.reports().live();
        if (live.isEmpty()) {
            moderation.messages().send(sender, "moderation.report.queue-empty");
            return;
        }
        moderation.chat().tell(sender, "<gray><count> report(s) in the queue:",
                Chat.arg("count", live.size()));

        Instant now = Instant.now();
        for (Report report : live.subList(0, Math.min(MOST_LINES, live.size()))) {
            moderation.chat().row(sender,
                    "<dark_gray><id></dark_gray> <white><subject></white> <gray>— <text> "
                            + "<dark_gray>(<when>, <state>)",
                    Chat.arg("id", report.id()),
                    Chat.arg("subject", report.subjectName()),
                    Chat.arg("text", report.text()),
                    Chat.arg("when", Times.brief(Duration.between(report.at(), now)) + " ago"),
                    Chat.arg("state", report.handlerId().isPresent()
                            ? "with " + report.handlerName() : report.state().describe()));
        }
        if (live.size() > MOST_LINES) {
            moderation.messages().send(sender, "moderation.report.queue-more",
                    "count", live.size() - MOST_LINES);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("list") : List.of();
    }
}
