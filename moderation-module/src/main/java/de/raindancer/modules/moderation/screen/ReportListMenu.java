package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.platform.util.Wrapping;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportPreset;
import de.raindancer.modules.moderation.model.ReportSeverity;
import de.raindancer.modules.moderation.rules.ReportSeverityRule;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A list of reports — the queue, the archive, or everything around one player. Each one is coloured by
 * how bad it looks, so the eye goes to the red ones first; live ones are sorted worst first, then longest
 * waiting, closed ones newest first.
 */
public final class ReportListMenu extends ModerationList<Report> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ReportSeverityRule SEVERITY = new ReportSeverityRule();
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.ROOT)
            .withZone(LogSearchService.ZONE);

    /** Which reports a list shows. */
    public enum Filter {
        WAITING("Waiting"), HANDLED("Being handled"), MINE("Mine"), CLOSED("Closed"), ALL("All reports"),
        /** About one player, or filed by them. */
        INVOLVING("Involving");

        private final String title;

        Filter(String title) {
            this.title = title;
        }

        public Predicate<Report> test(UUID viewer, UUID player) {
            return switch (this) {
                case WAITING -> Report::isWaiting;
                case HANDLED -> report -> report.isLive() && !report.isWaiting();
                case MINE -> report -> report.isLive() && report.handlerId().map(viewer::equals).orElse(false);
                case CLOSED -> Report::isClosed;
                case ALL -> report -> true;
                case INVOLVING -> report -> report.subject().equals(player)
                        || report.reporterId().map(id -> id.equals(player)).orElse(false);
            };
        }
    }

    private final Filter filter;
    private final UUID player;
    private final String playerName;

    public ReportListMenu(ModerationServices services, Player viewer, Menu parent, Filter filter, UUID player,
                          String playerName) {
        super(services, viewer, parent);
        this.filter = filter;
        this.player = player;
        this.playerName = playerName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<white>" + (filter == Filter.INVOLVING ? playerName : filter.title));
    }

    @Override
    public String breadcrumb() {
        return filter == Filter.INVOLVING ? playerName : filter.title;
    }

    @Override
    protected List<Report> entries() {
        List<Report> all = services().reports().all();
        Comparator<Report> worstFirst = Comparator
                .comparing((Report report) -> report.isLive() ? 0 : 1)
                .thenComparing(report -> report.isLive() ? -SEVERITY.judge(report, all).ordinal() : 0)
                .thenComparing(report -> report.isLive() ? report.at() : Instant.MIN)
                .thenComparing(report -> report.closedAt() == null ? Instant.MIN : report.closedAt(),
                        Comparator.reverseOrder());
        return all.stream().filter(filter.test(viewer.getUniqueId(), player)).sorted(worstFirst).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing here", "<gray>No report fits this list.");
    }

    @Override
    protected ItemStack icon(Report report) {
        return card(report, services().reports().all(), player, true);
    }

    /** One report as a single item: the colour, who, what, when, and where it stands. */
    static ItemStack card(Report report, List<Report> all, UUID involving, boolean clickable) {
        ReportSeverity severity = SEVERITY.judge(report, all);
        ReportPreset preset = ReportPreset.of(report);
        List<String> lore = new ArrayList<>();
        lore.add(severity.bar() + " " + severity.colour() + severity.title() + " <dark_gray>· <gray>" + preset.title());
        lore.add("");
        lore.addAll(Wrapping.wrap(MINI.escapeTags(report.text()), 40, "<white>"));
        lore.add("");
        lore.add("<gray>Filed by <white>" + report.reporterName() + "</white>, " + ago(report.at())
                + " <dark_gray>(" + WHEN.format(report.at()) + ")");
        if (involving != null && report.reporterId().map(involving::equals).orElse(false)
                && !report.subject().equals(involving)) {
            lore.add("<aqua>Filed by them, not about them.");
        }
        lore.add("<gray>" + report.state().describe() + (report.handlerId().isPresent() ? " by <white>" + report.handlerName() : ""));
        if (report.isClosed()) {
            lore.add("<gray>“" + MINI.escapeTags(report.outcome()) + "”");
        }
        if (clickable) {
            lore.add("");
            lore.add("<dark_gray>Click: the whole report card.");
            if (report.isLive()) {
                lore.add("<yellow>right click <dark_gray>dismiss it");
            }
        }
        Material icon = report.isLive() ? severity.icon() : Material.LIGHT_GRAY_CONCRETE;
        String name = (report.isLive() ? severity.colour() : "<gray>") + report.subjectName()
                + " <dark_gray>· " + report.id();
        return Icons.of(icon, name, lore);
    }

    /** "3 hours ago", or "just now" for less than a minute, which Core's wording leaves empty. */
    static String ago(Instant when) {
        Duration since = Duration.between(when, Instant.now());
        String said = Times.describe(since);
        return since.toMinutes() < 1 || said.isBlank() ? "just now" : said + " ago";
    }

    @Override
    protected void onClick(Report report, InventoryClickEvent event) {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        if (event.isRightClick() && report.isLive()) {
            // The quick way through a queue of nonsense: closed as "nothing in it", and the reporter is told.
            boolean closed = services().reportService().reject(report.id(), viewer.getUniqueId(),
                    viewer.getName(), "Dismissed.");
            tell(closed ? "moderation.report.dismissed" : "moderation.report.already-closed", "id", report.id());
            refresh();
            return;
        }
        new ReportMenu(services(), viewer, this, report.id()).open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Red is critical, orange high, yellow medium, green low.",
                "<gray>Live ones worst first; closed ones newest first.");
    }

    @Override
    public String describe() {
        return "a list of reports, coloured by how bad each looks";
    }
}
