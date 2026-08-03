package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /history} — what has happened to somebody.
 *
 * <h2>Why it answers in chat when a screen exists</h2>
 * Because a record is a thing people quote. A moderator answering an appeal wants to copy two lines out
 * of it into a reply, and a chest menu is the one shape you cannot copy from. The screen is one click
 * away for somebody who wants to <em>act</em> on what they read.
 *
 * <p>Everything is shown, lifted punishments included. Core never deletes one, and the whole point of
 * that is answering "is this a first offence".
 */
public final class HistoryCommand extends StaffCommand {

    /** How many lines is a useful answer in chat rather than a wall of them. */
    private static final int MOST_LINES = 10;

    public HistoryCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.HISTORY);
    }

    @Override
    public String describe() {
        return "what has happened to somebody, newest first";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage", "usage", "/history <player>");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);

        List<Punishment> record = moderation.punishmentService().history(them.getUniqueId())
                .stream()
                .sorted(Comparator.comparing(Punishment::givenAt).reversed())
                .toList();
        if (record.isEmpty()) {
            moderation.messages().send(sender, "moderation.record-empty", "player", name);
            return;
        }
        moderation.chat().tell(sender, "<gray>Record for <white><player></white> — "
                + "<white><count></white> entr(ies):",
                Chat.arg("player", name), Chat.arg("count", record.size()));

        Instant now = Instant.now();
        for (Punishment past : record.subList(0, Math.min(MOST_LINES, record.size()))) {
            moderation.chat().row(sender,
                    "<white><what></white> <gray>— <reason> <dark_gray>(<when>, <length>)</dark_gray><state>",
                    Chat.arg("what", past.kind().past()),
                    Chat.arg("reason", past.reason()),
                    Chat.arg("when", Times.describe(Duration.between(past.givenAt(), now)) + " ago"),
                    Chat.arg("length", past.length()),
                    Chat.arg("state", stateOf(past, now)));
        }
        if (record.size() > MOST_LINES) {
            moderation.messages().send(sender, "moderation.record-more",
                    "count", record.size() - MOST_LINES, "player", name);
        }
        if (sender instanceof Player reader) {
            moderation.messages().send(reader, "moderation.record-see-screen", "player", name);
        }
    }

    /** Lifted, still in force, or over — the three things a line of a record has to distinguish. */
    private static String stateOf(Punishment past, Instant now) {
        if (past.liftedAt() != null) {
            return " <green>lifted";
        }
        if (past.isActiveAt(now)) {
            return " <red>in force";
        }
        return "";
    }
}
