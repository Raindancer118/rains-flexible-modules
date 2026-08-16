package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.audit.AuditSearch;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The audit journal, one page at a time.
 *
 * <h2>Why this exists next to {@code /audit}</h2>
 * {@link de.raindancer.modules.moderation.command.AuditCommand}'s own javadoc gives the reason
 * chat is the command's answer: a record is a thing people quote, and a chest menu is the one shape
 * you cannot copy from. That reason is still true and this screen does not fight it — it does not
 * replace the command, it is the one click away from it, for browsing rather than quoting: scrolling
 * back through what happened without retyping a player's name for every page, the way
 * {@link HistoryMenu} sits next to {@code /history} for the same reason.
 *
 * <h2>Global or one player, the same page</h2>
 * A null {@code subject} reads Core's journal whole, newest first — what {@code /audit} shows with no
 * argument. A subject reads it the same way {@code /audit <player>} does: both directions, actor and
 * subject, merged so a lookup by player never hides half of what they were involved in.
 */
public final class AuditMenu extends ModerationList<AuditEntry> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Read generously — this is a scrolling page, not a fifteen-line chat wall. */
    private static final int SEARCH_LIMIT = 300;

    private final UUID subject;
    private final String subjectName;

    /** The whole journal. */
    public AuditMenu(ModerationServices services, Player viewer, Menu parent) {
        this(services, viewer, parent, null, null);
    }

    /** One player's part of it, actor and subject merged. */
    public AuditMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                     String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(subject == null
                ? "<dark_gray>Audit journal"
                : "<dark_gray>Audit — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return subject == null ? "Audit" : "Audit";
    }

    @Override
    protected List<AuditEntry> entries() {
        if (subject == null) {
            return services().audit().search(AuditSearch.everything().limit(SEARCH_LIMIT));
        }
        // Both directions, merged and de-duplicated by id, newest first — the same shape as
        // AuditCommand.merged, kept here rather than shared because a chat line and a menu icon read
        // an entry differently and the merge is four lines, not worth a dependency between them.
        Map<Long, AuditEntry> byId = new LinkedHashMap<>();
        for (AuditEntry entry : services().audit().search(AuditSearch.by(subject).limit(SEARCH_LIMIT))) {
            byId.put(entry.id(), entry);
        }
        for (AuditEntry entry :
                services().audit().search(AuditSearch.to(subject).limit(SEARCH_LIMIT))) {
            byId.put(entry.id(), entry);
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(AuditEntry::at).reversed())
                .toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return subject == null
                ? Icons.of(Material.COBWEB, "<gray>Nothing in the audit journal yet")
                : Icons.of(Material.COBWEB, "<gray>Nothing on record",
                        "<gray>" + subjectName + " has not touched the journal yet.");
    }

    @Override
    protected ItemStack icon(AuditEntry entry) {
        String ago = Times.describe(Duration.between(entry.at(), Instant.now())) + " ago";
        // Direction only matters on the whole-journal page — a player's own page already knows who
        // they are, and repeating their name on every line of their own history is the kind of noise
        // that makes a page harder to scan, not easier.
        List<String> lore = subject == null
                ? List.of("<gray>" + entry.saying(), "<dark_gray>" + ago)
                : List.of("<gray>" + entry.saying(), "<dark_gray>" + ago,
                        "", "<dark_gray>Click to read it in chat, where it can be copied.");
        return Icons.of(Material.WRITTEN_BOOK, "<yellow>" + entry.feature(), lore);
    }

    @Override
    protected void onClick(AuditEntry entry, InventoryClickEvent event) {
        // Read, not acted on — the journal is a record of what already happened, and the one useful
        // thing a click can do with one line of it is put it back in chat, quotable, which is the
        // whole reason /audit answers in chat in the first place.
        tell("moderation.audit-line-chat", "when",
                Times.describe(Duration.between(entry.at(), Instant.now())) + " ago",
                "feature", entry.feature(), "saying", entry.saying());
    }

    @Override
    protected List<String> helpLines() {
        return subject == null
                ? List.of("<gray>Every join, teleport, gamemode change and moderation action,",
                        "<gray>newest first — everything Core's journal has kept.")
                : List.of("<gray>What " + subjectName + " has done, and what has been done to them.");
    }

    @Override
    public String describe() {
        return subject == null
                ? "the audit journal, newest first, for browsing rather than quoting"
                : "one player's part of the audit journal, both directions merged";
    }
}
