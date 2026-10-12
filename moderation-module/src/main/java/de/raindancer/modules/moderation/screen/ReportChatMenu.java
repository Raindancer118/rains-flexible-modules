package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.platform.util.Wrapping;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
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
import java.util.Locale;

/**
 * The conversation around a report: everybody's lines from ten minutes before it was filed to three after,
 * oldest first. The reported player's lines stand out, the reporter's are marked — a slur reads differently
 * once the five lines before it are on screen too.
 */
public final class ReportChatMenu extends ModerationList<ChatLog.Line> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Duration BEFORE = Duration.ofMinutes(10);
    private static final Duration AFTER = Duration.ofMinutes(3);
    private static final int MOST = 300;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .withZone(LogSearchService.ZONE);

    private final Report report;
    /** Null until read: the window opens once they are. */
    private volatile List<ChatLog.Line> lines;

    public ReportChatMenu(ModerationServices services, Player viewer, Menu parent, Report report) {
        super(services, viewer, parent);
        this.report = report;
    }

    @Override
    public void open() {
        if (!may(ModerationPermission.CHATLOG)) {
            tell("moderation.no-permission");
            return;
        }
        if (lines != null) {
            super.open();
            return;
        }
        Scheduling.async(services().plugin(), () -> {
            lines = List.copyOf(services().logSearch().around(report.at(), BEFORE, AFTER, MOST));
            Scheduling.entity(services().plugin(), viewer, super::open);
        });
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<white>Chat around " + report.id());
    }

    @Override
    public String breadcrumb() {
        return "Chat around it";
    }

    @Override
    protected List<ChatLog.Line> entries() {
        return lines == null ? List.of() : lines;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing was said around then",
                "<gray>Or the chat log was off, or has forgotten it.");
    }

    @Override
    protected ItemStack icon(ChatLog.Line line) {
        boolean theirs = line.player().equals(report.subject());
        boolean reporter = report.reporterId().map(line.player()::equals).orElse(false);
        List<String> lore = new ArrayList<>(Wrapping.wrap(MINI.escapeTags(line.text()), 40, theirs ? "<yellow>" : "<white>"));
        lore.add("");
        long seconds = Duration.between(report.at(), Instant.ofEpochMilli(line.at())).toSeconds();
        lore.add("<dark_gray>" + CLOCK.format(Instant.ofEpochMilli(line.at())) + " · "
                + (seconds < 0 ? -seconds + " s before the report" : seconds + " s after it")
                + (ChatLog.PUBLIC.equals(line.channel()) ? "" : " · " + MINI.escapeTags(line.channel())));
        if (theirs) {
            lore.add("<gold>The reported player.");
        } else if (reporter) {
            lore.add("<aqua>Who filed the report.");
        }
        String head = line.text().length() > 30 ? line.text().substring(0, 30) + "…" : line.text();
        Material icon = theirs ? Material.WRITABLE_BOOK : reporter ? Material.BOOK : Material.PAPER;
        String colour = theirs ? "<yellow>" : reporter ? "<aqua>" : "<gray>";
        return Icons.of(icon, colour + MINI.escapeTags(line.name()) + ": <white>" + MINI.escapeTags(head), lore);
    }

    @Override
    protected void onClick(ChatLog.Line line, InventoryClickEvent event) {
        // Reading only — but a page can stay open while the right to read it is taken away.
        if (!may(ModerationPermission.CHATLOG)) {
            tell("moderation.no-permission");
            viewer.closeInventory();
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Ten minutes before the report to three after, oldest first.",
                "<gray>Yellow: the reported player. Blue: who reported them.");
    }

    @Override
    public String describe() {
        return "the conversation around a report, everybody's lines";
    }
}
