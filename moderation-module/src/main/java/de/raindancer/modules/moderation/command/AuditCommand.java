package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.audit.AuditSearch;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /audit} — the in-game window onto Core's audit journal.
 *
 * <h2>Why this reads Core's journal rather than keeping its own</h2>
 * Because there is one journal, not one per plugin that happens to call {@code Audit.record}. Joins,
 * teleports, gamemode changes and every moderation action already land in the same table, and a second
 * copy here would be the exact duplication this library exists to avoid.
 *
 * <h2>Actor or subject, merged</h2>
 * A name typed here can mean either "what did they do" or "what was done to them", and both are real
 * questions a moderator has about one player — usually at once. So a lookup by player reads the journal
 * twice, once as actor and once as subject, and merges the two, newest first, rather than making
 * somebody run the command twice to see the whole picture.
 *
 * <h2>Why chat rather than a screen</h2>
 * The same reason as {@link HistoryCommand}: a record is a thing people quote, and a chest menu is the
 * one shape you cannot copy from. The screen — {@link de.raindancer.modules.moderation.screen.AuditMenu}
 * — is one click away, the same way {@link HistoryMenu} sits next to {@code /history}: for scrolling
 * back through the journal a page at a time, not for the line somebody is about to paste into a reply.
 */
public final class AuditCommand extends StaffCommand {

    /** How many lines is a useful answer in chat rather than a wall of them. */
    private static final int MOST_LINES = 15;

    public AuditCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.HISTORY);
    }

    @Override
    public String describe() {
        return "the audit journal — joins, teleports, gamemode changes, moderation actions";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length == 0) {
            List<AuditEntry> found =
                    moderation.audit().search(AuditSearch.everything().limit(MOST_LINES));
            show(moderation, sender, found, null);
            return;
        }

        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);
        String feature = args.length >= 2 ? args[1] : null;
        show(moderation, sender, merged(moderation, them.getUniqueId(), feature), name);
    }

    private void show(ModerationServices moderation, CommandSender sender, List<AuditEntry> found,
                      String player) {
        Messages messages = moderation.messages();
        if (found.isEmpty()) {
            if (player == null) {
                moderation.messages().send(sender, "moderation.audit-empty");
            } else {
                moderation.messages().send(sender, "moderation.audit-empty-for", "player", player);
            }
            return;
        }

        if (player == null) {
            moderation.chat().tell(sender, messages.raw("moderation.audit-heading"),
                    Chat.arg("count", found.size()));
        } else {
            moderation.chat().tell(sender, messages.raw("moderation.audit-heading-for"),
                    Chat.arg("player", player), Chat.arg("count", found.size()));
        }

        Instant now = Instant.now();
        for (AuditEntry entry : found.subList(0, Math.min(MOST_LINES, found.size()))) {
            moderation.chat().row(sender, messages.raw("moderation.audit-line"),
                    Chat.arg("when", Times.describe(Duration.between(entry.at(), now)) + " ago"),
                    Chat.arg("feature", entry.feature()),
                    Chat.formatted("saying", Component.text(entry.saying())));
        }
        if (found.size() > MOST_LINES) {
            moderation.messages().send(sender, "moderation.audit-more",
                    "count", found.size() - MOST_LINES);
        }
        // The screen, one click away — for browsing rather than quoting. See AuditMenu's javadoc for
        // why that split does not undo the reason this command answers in chat.
        if (sender instanceof Player reader) {
            if (player == null) {
                moderation.messages().send(reader, "moderation.audit-see-screen");
            } else {
                moderation.messages().send(reader, "moderation.audit-see-screen-for", "player", player);
            }
        }
    }

    /**
     * Both directions, merged and sorted newest first.
     *
     * <p>Read twice at {@link #MOST_LINES} each rather than once at a larger limit and cut down
     * afterwards: a player who is mostly a subject (banned twice, warned once) and rarely an actor
     * (one join) must not have their one join pushed out by somebody else's history because the actor
     * query came back empty and the subject query's cap ate the whole budget.
     */
    private static List<AuditEntry> merged(ModerationServices moderation, UUID who, String feature) {
        AuditSearch asActor = AuditSearch.by(who).limit(MOST_LINES);
        AuditSearch asSubject = AuditSearch.to(who).limit(MOST_LINES);
        if (feature != null && !feature.isBlank()) {
            asActor = asActor.withFeature(feature);
            asSubject = asSubject.withFeature(feature);
        }
        Map<Long, AuditEntry> byId = new LinkedHashMap<>();
        for (AuditEntry entry : moderation.audit().search(asActor)) {
            byId.put(entry.id(), entry);
        }
        for (AuditEntry entry : moderation.audit().search(asSubject)) {
            byId.put(entry.id(), entry);
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(AuditEntry::at).reversed())
                .toList();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services().server(), args.length == 1 ? args[0] : "");
        }
        return List.of();
    }
}
