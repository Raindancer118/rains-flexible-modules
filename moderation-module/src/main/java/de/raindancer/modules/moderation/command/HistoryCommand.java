package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
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
        // Wording out of messages.yml, like everything else a player reads. The format string is
        // raw() rather than get() because the line is finished by Chat below — get() would render it
        // with its placeholders still in it.
        Messages messages = moderation.messages();
        moderation.chat().tell(sender, messages.raw("moderation.record-heading"),
                Chat.arg("player", name), Chat.arg("count", record.size()));

        Instant now = Instant.now();
        for (Punishment past : record.subList(0, Math.min(MOST_LINES, record.size()))) {
            moderation.chat().row(sender, messages.raw("moderation.record-line"),
                    Chat.arg("what", past.kind().past()),
                    Chat.arg("reason", past.reason()),
                    Chat.arg("when", Times.describe(Duration.between(past.givenAt(), now)) + " ago"),
                    Chat.arg("length", past.length()),
                    // formatted, not arg. The state is wording *this plugin* wrote and it carries a
                    // colour; arg() escapes what it is given — always, and rightly, because most of
                    // what goes through it is text a player typed. Passed through arg() this line
                    // printed "<green>lifted" at somebody, which is what a leaked tag looks like from
                    // the other direction: an opening one, shown rather than applied.
                    Chat.formatted("state", stateOf(messages, past, now)));
        }
        if (record.size() > MOST_LINES) {
            moderation.messages().send(sender, "moderation.record-more",
                    "count", record.size() - MOST_LINES, "player", name);
        }
        if (sender instanceof Player reader) {
            moderation.messages().send(reader, "moderation.record-see-screen", "player", name);
        }
    }

    /**
     * Lifted, still in force, or over — the three things a line of a record has to distinguish.
     *
     * <p>A {@link Component} rather than a string of markup, because that is the only form that can
     * be put into a message without being escaped on the way in. Its words come from
     * {@code messages.yml} like every other word here; "over" has none, since a punishment that has
     * simply expired needs no adjective after its own dates.
     */
    private static Component stateOf(Messages messages, Punishment past, Instant now) {
        if (past.liftedAt() != null) {
            return messages.get("moderation.record-state.lifted");
        }
        if (past.isActiveAt(now)) {
            return messages.get("moderation.record-state.in-force");
        }
        return Component.empty();
    }
}
