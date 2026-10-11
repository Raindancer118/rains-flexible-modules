package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.platform.util.Wrapping;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.LogQuery;
import de.raindancer.modules.moderation.service.LogSearchService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What one player said, newest first, and a search through it — the window form of {@code /chatlog player:<them>}.
 * Read off the server's threads before the window opens; see {@link #open()}.
 */
public final class ChatLogMenu extends ModerationList<ChatLog.Line> {

    /** How many lines one look reads; a search narrows it, /chatlog pages through the rest. */
    public static final int MOST_LINES = 450;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(LogSearchService.ZONE);

    private final UUID subject;
    private final String subjectName;
    private final String search;
    /** Null until read: the window opens once they are. */
    private volatile List<ChatLog.Line> lines;

    public ChatLogMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName,
                       String search) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
        this.search = search == null ? "" : search.strip();
    }

    @Override
    public void open() {
        if (lines != null) {
            super.open();
            return;
        }
        String[] typed = ("player:" + subject + " " + search).split(" ");
        LogQuery query = LogQuery.parse(typed, LogSearchService.ZONE, Instant.now());
        Scheduling.async(services().plugin(), () -> {
            lines = List.copyOf(services().logSearch().chat(query, MOST_LINES).items());
            Scheduling.entity(services().plugin(), viewer, super::open);
        });
    }

    @Override
    protected Component title() {
        return Component.text(search.isEmpty() ? subjectName + " said" : subjectName + ": " + search);
    }

    @Override
    public String breadcrumb() {
        return "Chat log";
    }

    @Override
    protected List<ChatLog.Line> entries() {
        return lines == null ? List.of() : lines;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, search.isEmpty() ? "<gray>Nothing said, or nothing kept"
                : "<gray>Nothing matches " + escaped(search));
    }

    @Override
    protected ItemStack icon(ChatLog.Line line) {
        List<String> lore = new ArrayList<>(Wrapping.wrap(escaped(line.text()), 40, "<white>"));
        lore.add("");
        lore.add("<dark_gray>" + WHEN.format(Instant.ofEpochMilli(line.at())) + " (Berlin)"
                + (ChatLog.PUBLIC.equals(line.channel()) ? "" : " · " + escaped(line.channel())));
        if (!line.name().equals(subjectName)) {
            lore.add("<dark_gray>said as " + escaped(line.name()));
        }
        String head = line.text().length() > 30 ? line.text().substring(0, 30) + "…" : line.text();
        return Icons.of(Material.PAPER, "<gray>" + escaped(head), lore);
    }

    @Override
    protected void onClick(ChatLog.Line line, InventoryClickEvent event) {
        // Reading only.
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.SPYGLASS, search.isEmpty() ? "<yellow>Search" : "<yellow>Search again",
                        "<gray>Lines holding every word you type;",
                        "<gray>\"quotes\" for a phrase, from:/to:/day: for a time.",
                        search.isEmpty() ? "" : "<dark_gray>right click: everything again"),
                click -> {
                    if (click.isRightClick()) {
                        new ChatLogMenu(services(), viewer, parent(), subject, subjectName, "").open();
                        return;
                    }
                    viewer.closeInventory();
                    tell("moderation.chatlog.type-search");
                    services().prompts().ask(viewer.getUniqueId(), "Moderation", Duration.ofMinutes(1),
                            typed -> new ChatLogMenu(services(), viewer, parent(), subject, subjectName, typed).open(),
                            () -> new ChatLogMenu(services(), viewer, parent(), subject, subjectName, search).open());
                });
    }

    private static String escaped(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    @Override
    public String describe() {
        return "everything somebody said in public chat, and a search through it";
    }
}
