package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportSeverity;
import de.raindancer.modules.moderation.rules.ReportSeverityRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The reports: what is waiting, what somebody has, what is done — and a way to find everything about one
 * player. Each door says how many are behind it and how bad the worst of them is, so the hub alone answers
 * "is anything urgent".
 *
 * <pre>
 *  row 1   Queue     [waiting]  ·  [being handled]  ·  [mine]
 *  row 2   Archive        [closed]  ·  [all]
 *  row 3   Find      [reported players]  ·  [search by name]
 * </pre>
 */
public final class ReportsMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ReportSeverityRule SEVERITY = new ReportSeverityRule();

    public ReportsMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    /** Asked here, not only by the door that led here: a report link in chat or a typed id reaches this too. */
    @Override
    public void open() {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        super.open();
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<white>Reports");
    }

    @Override
    public String breadcrumb() {
        return "Reports";
    }

    @Override
    protected void render() {
        List<Report> all = services().reports().all();
        boolean allowed = may(ModerationPermission.REPORTS);
        long waiting = all.stream().filter(Report::isWaiting).count();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BELL, "<white>Reports",
                "<gray>" + waiting + " waiting, " + all.stream().filter(Report::isLive).count() + " live, "
                        + all.size() + " in all."));

        group(MenuLayout.WHO, Material.RED_STAINED_GLASS_PANE, "<red>Queue");
        group(MenuLayout.RULES, Material.GRAY_STAINED_GLASS_PANE, "<gray>Archive");
        group(MenuLayout.LAND, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Find");

        door(MenuLayout.WHO, 2, allowed, all, ReportListMenu.Filter.WAITING, Material.BELL, "Waiting",
                "Nobody has picked these up yet.");
        door(MenuLayout.WHO, 4, allowed, all, ReportListMenu.Filter.HANDLED, Material.SPYGLASS, "Being handled",
                "Somebody is on these.");
        door(MenuLayout.WHO, 6, allowed, all, ReportListMenu.Filter.MINE, Material.PLAYER_HEAD, "Mine",
                "The ones you picked up.");
        door(MenuLayout.RULES, 3, allowed, all, ReportListMenu.Filter.CLOSED, Material.LIME_DYE, "Closed",
                "Dealt with, or nothing in it.");
        door(MenuLayout.RULES, 5, allowed, all, ReportListMenu.Filter.ALL, Material.BOOKSHELF, "All",
                "Everything ever reported.");

        band(MenuLayout.LAND, 3, allowed, Icons.of(Material.PLAYER_HEAD, "<yellow>Reported players",
                        "<gray>Everybody with a report about them,",
                        "<gray>most live reports first."),
                "For whoever may read reports",
                click -> new ReportedPlayersMenu(services(), viewer, this).open());
        band(MenuLayout.LAND, 5, allowed, Icons.of(Material.NAME_TAG, "<yellow>Search by name",
                        "<gray>Every report about somebody,",
                        "<gray>and every one they filed.", "<dark_gray>You type the name."),
                "For whoever may read reports",
                click -> search());
    }

    private void group(int band, Material pane, String title) {
        set(band * 9, Icons.of(pane, title));
        set(band * 9 + 8, Icons.of(pane, title));
    }

    private void door(int band, int column, boolean allowed, List<Report> all, ReportListMenu.Filter filter,
                      Material icon, String title, String what) {
        Predicate<Report> shown = filter.test(viewer.getUniqueId(), null);
        List<Report> behind = all.stream().filter(shown).toList();
        List<String> lore = new ArrayList<>();
        lore.add("<white>" + behind.size() + "</white><gray> " + (behind.size() == 1 ? "report" : "reports") + ".");
        worst(behind, all).ifPresent(severity -> lore.add(severity.bar() + " <gray>worst: " + severity.colour()
                + severity.title()));
        lore.add("");
        lore.add("<dark_gray>" + what);
        band(band, column, allowed, Icons.of(icon, "<yellow>" + title, lore), "For whoever may read reports",
                click -> new ReportListMenu(services(), viewer, this, filter, null, null).open());
    }

    /** The worst of what is still live behind a door; closed ones no longer need anybody to hurry. */
    private static Optional<ReportSeverity> worst(List<Report> behind, List<Report> all) {
        return behind.stream().filter(Report::isLive).map(report -> SEVERITY.judge(report, all))
                .max(Comparator.naturalOrder());
    }

    /** Asks for a name, then lists every report about them or by them. */
    private void search() {
        viewer.closeInventory();
        tell("moderation.report.type-name");
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(60),
                typed -> {
                    String wanted = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
                    Optional<Report> match = services().reports().all().stream()
                            .filter(report -> report.subjectName().toLowerCase(Locale.ROOT).equals(wanted)
                                    || report.reporterName().toLowerCase(Locale.ROOT).equals(wanted))
                            .findFirst()
                            .or(() -> services().reports().all().stream()
                                    .filter(report -> report.subjectName().toLowerCase(Locale.ROOT).startsWith(wanted))
                                    .findFirst());
                    if (wanted.isEmpty() || match.isEmpty()) {
                        tell("moderation.report.nobody-involved", "player", typed == null ? "" : typed.strip());
                        return;
                    }
                    Report found = match.get();
                    boolean subject = found.subjectName().toLowerCase(Locale.ROOT).startsWith(wanted);
                    UUID who = subject ? found.subject() : found.reporterId().orElse(found.subject());
                    String name = subject ? found.subjectName() : found.reporterName();
                    new ReportListMenu(services(), viewer, this, ReportListMenu.Filter.INVOLVING, who, name).open();
                },
                () -> tell("moderation.nothing-typed"));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Each door: how many, and how bad the worst live one is.",
                "<gray>Pick one up before you walk over, so nobody else does too.");
    }

    @Override
    public String describe() {
        return "the reports: the queue, the archive, and finding everything about one player";
    }
}
