package de.raindancer.modules.moderation.screen;

import de.raindancer.core.platform.util.Wrapping;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportGroup;
import de.raindancer.modules.moderation.model.ReportPlace;
import de.raindancer.modules.moderation.model.ReportPreset;
import de.raindancer.modules.moderation.model.ReportSeverity;
import de.raindancer.modules.moderation.rules.ReportSeverityRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One report, and everything needed to decide it without leaving the card: what it is about and how bad
 * it looks, the whole text, when and by whom; the reported player's page, the chat around it, the other
 * reports about them and their evidence; where both stood; and the decision.
 *
 * <pre>
 *  row 0   ·  ·  ·  [severity] [reported] [reporter]  ·  ·  ·
 *  row 1   What happened   [reason]  ·  [what they wrote]  ·  [when and state]
 *  row 2   Look into       [their page] [chat around it] · [other reports] [evidence]
 *  row 3   Places          [where it was reported]  ·  [where they were]  ·  [go to them now]
 *  row 4             [pick up]  [dealt with]  ·  [nothing in it]  [punish]
 * </pre>
 *
 * <h2>Why the outcome is a sentence</h2>
 * Because the outcome is what the player who filed it is told, and "resolved" tells them nothing. The
 * moderator types a line, and the reporter gets it.
 */
public final class ReportMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ReportSeverityRule SEVERITY = new ReportSeverityRule();
    private static final DateTimeFormatter TO_THE_SECOND = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
            .withZone(de.raindancer.modules.moderation.service.LogSearchService.ZONE);

    private final String reportId;

    public ReportMenu(ModerationServices services, Player viewer, Menu parent, String reportId) {
        super(services, viewer, parent);
        this.reportId = reportId;
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
        return MINI.deserialize("<white>Report " + reportId);
    }

    @Override
    public String breadcrumb() {
        return reportId;
    }

    @Override
    protected void render() {
        Optional<Report> found = services().reports().byId(reportId);
        if (found.isEmpty()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.COBWEB, "<gray>This report is gone",
                    "<gray>Somebody removed it, or the file was edited."));
            return;
        }
        Report report = found.get();
        List<Report> all = services().reports().all();
        ReportSeverity severity = SEVERITY.judge(report, all);
        ReportPreset preset = ReportPreset.of(report);
        boolean allowed = may(ModerationPermission.REPORTS);
        List<Report> aboutThem = all.stream().filter(other -> other.subject().equals(report.subject())).toList();

        // ── header ──────────────────────────────────────────────────────────────────────────────
        List<String> why = new ArrayList<>();
        why.add(severity.bar());
        why.add("<gray>" + preset.title() + " is " + ReportSeverity.of(preset.severity()).title().toLowerCase(Locale.ROOT)
                + " on its own.");
        long reporters = aboutThem.stream().filter(Report::isLive).map(other -> other.reporterId().orElse(null))
                .distinct().count();
        if (severity.ordinal() + 1 > preset.severity()) {
            why.add("<gray>Raised: " + reporters + " people have live reports about them.");
        }
        set(MenuLayout.HEADER_SUBJECT - 1, Icons.of(report.isLive() ? severity.icon() : Material.LIGHT_GRAY_CONCRETE,
                severity.colour() + severity.title(), why));
        set(MenuLayout.HEADER_SUBJECT, Icons.head(report.subject(), "<white>" + report.subjectName(),
                "<gray>The reported player.", "<dark_gray>" + aboutThem.size() + " report(s) about them in all."));
        report.reporterId().ifPresentOrElse(
                reporter -> set(MenuLayout.HEADER_SUBJECT + 1, Icons.head(reporter, "<white>" + report.reporterName(),
                        "<gray>Filed it.", "<dark_gray>" + all.stream().filter(other -> other.reporterId()
                                .map(reporter::equals).orElse(false)).count() + " report(s) filed by them.")),
                () -> set(MenuLayout.HEADER_SUBJECT + 1, Icons.of(Material.COMMAND_BLOCK, "<white>The server",
                        "<gray>Filed by a check, not a person.")));

        // ── what happened ───────────────────────────────────────────────────────────────────────
        group(MenuLayout.WHO, Material.YELLOW_STAINED_GLASS_PANE, "<yellow>What happened");
        band(MenuLayout.WHO, 2, Icons.of(preset.icon(), "<yellow>" + preset.title(),
                "<gray>" + preset.group().title() + ".",
                preset.chat() ? "<dark_gray>What was said around it is evidence." : "<dark_gray>Reported as this."));
        List<String> text = new ArrayList<>(Wrapping.wrap(MINI.escapeTags(report.text()), 40, "<white>"));
        band(MenuLayout.WHO, 4, Icons.of(Material.WRITTEN_BOOK, "<yellow>What they wrote", text));
        List<String> when = new ArrayList<>();
        when.add("<gray>Filed " + TO_THE_SECOND.format(report.at()) + " (Berlin),");
        when.add("<gray>" + ReportListMenu.ago(report.at()) + ".");
        when.add("<gray>" + report.state().describe() + (report.handlerId().isPresent() ? " by <white>" + report.handlerName() : "") + ".");
        if (report.isClosed()) {
            when.add("<gray>Closed " + TO_THE_SECOND.format(report.closedAt()) + ":");
            when.addAll(Wrapping.wrap("“" + MINI.escapeTags(report.outcome()) + "”", 40, "<white>"));
        }
        band(MenuLayout.WHO, 6, Icons.of(report.state().icon(), "<yellow>When, and where it stands", when));

        // ── look into ───────────────────────────────────────────────────────────────────────────
        group(MenuLayout.RULES, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Look into");
        band(MenuLayout.RULES, 2, allowed, Icons.of(Material.SPYGLASS, "<yellow>Their page",
                        "<gray>Everything about " + report.subjectName() + ":", "<gray>record, punish, practical."),
                "For whoever may read reports",
                click -> new PlayerMenu(services(), viewer, this, report.subject(), report.subjectName()).open());
        boolean chatAllowed = may(ModerationPermission.CHATLOG);
        var chat = Icons.of(Material.OAK_HANGING_SIGN, "<yellow>Chat around it",
                "<gray>Everybody's lines from ten minutes before",
                "<gray>to three minutes after it was filed.",
                preset.chat() ? "<gold>This report is about chat." : "<dark_gray>Worth a look for any report.");
        band(MenuLayout.RULES, 3, chatAllowed, preset.chat() ? glowing(chat) : chat, "For whoever may read the chat log",
                click -> new ReportChatMenu(services(), viewer, this, report).open());
        band(MenuLayout.RULES, 5, allowed, Icons.of(Material.PAPER, "<yellow>Other reports about them",
                        "<gray>" + (aboutThem.size() - 1) + " besides this one.",
                        "<dark_gray>Including closed ones."),
                "For whoever may read reports",
                click -> new ReportListMenu(services(), viewer, this, ReportListMenu.Filter.INVOLVING, report.subject(),
                        report.subjectName()).open());
        evidence(report, preset, allowed);

        // ── places ──────────────────────────────────────────────────────────────────────────────
        group(MenuLayout.LAND, Material.LIME_STAINED_GLASS_PANE, "<green>Places");
        boolean teleport = viewer.hasPermission(PlayerScreen.TELEPORT);
        place(2, report.reporterAt(), "Where it was reported", report.reporterName() + " stood here when they filed it.", teleport);
        place(4, report.subjectAt(), "Where they were", report.subjectName() + " stood here at that moment.", teleport);
        Player online = Bukkit.getPlayer(report.subject());
        band(MenuLayout.LAND, 6, online != null && teleport, Icons.of(Material.ENDER_PEARL, "<yellow>Go to them now",
                        online != null ? "<green>Online." : "<gray>Not online."),
                online != null ? "For whoever may teleport" : "They are not online",
                click -> goToThem(report));

        // ── decide ──────────────────────────────────────────────────────────────────────────────
        if (report.isClosed()) {
            toolbar(4, Icons.of(Material.LIME_DYE, "<green>Closed", "<gray>“" + MINI.escapeTags(report.outcome()) + "”",
                    "<dark_gray>by " + report.handlerName()), click -> { });
            return;
        }
        toolbar(2, allowed ? (report.isWaiting()
                        ? Icons.of(Material.SPYGLASS, "<yellow>Pick it up", "<gray>Tells the other staff you are on it.")
                        : Icons.of(Material.SPYGLASS, "<yellow>Hand it back", "<gray>" + report.handlerName() + " has it.",
                        "<dark_gray>Puts it back in the queue."))
                        : Icons.locked(Icons.of(Material.SPYGLASS, "<yellow>Pick it up"), "For whoever may read reports"),
                click -> {
                    if (!allowed) {
                        tell("moderation.no-permission");
                        return;
                    }
                    if (report.isWaiting()) {
                        services().reportService().claim(reportId, viewer.getUniqueId(), viewer.getName());
                    } else {
                        services().reportService().release(reportId);
                    }
                    changed();
                });
        toolbar(3, Icons.of(Material.LIME_CONCRETE, "<green>Dealt with", "<gray>Something was done about it.",
                "<dark_gray>You will be asked what, and they are told."), click -> close(report, true, allowed));
        toolbar(5, Icons.of(Material.GRAY_CONCRETE, "<gray>Nothing in it", "<gray>Looked at, and there was nothing to do.",
                "<dark_gray>You will be asked why, and they are told."), click -> close(report, false, allowed));
        toolbar(6, Icons.of(Material.IRON_SWORD, "<red>Punish them", "<gray>Warn, kick, mute, ban " + report.subjectName() + ".",
                "<dark_gray>Close the report afterwards."),
                click -> new PlayerPunishMenu(services(), viewer, this, report.subject(), report.subjectName()).open());
    }

    /** Shimmering, so the button that matters for this kind of report is the one the eye finds. */
    private static org.bukkit.inventory.ItemStack glowing(org.bukkit.inventory.ItemStack item) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    private void group(int band, Material pane, String title) {
        set(band * 9, Icons.of(pane, title));
        set(band * 9 + 8, Icons.of(pane, title));
    }

    /** The evidence that fits what was reported: the mining record for x-ray, the anti-cheat for other cheating. */
    private void evidence(Report report, ReportPreset preset, boolean allowed) {
        if (preset == ReportPreset.XRAY) {
            band(MenuLayout.RULES, 6, allowed, Icons.of(Material.DIAMOND_PICKAXE, "<yellow>Mining evidence",
                            "<gray>Veins and bait ores, where and when."),
                    "For whoever may read reports",
                    click -> new XrayReviewMenu(services(), viewer, this, report.subject(), report.subjectName()).open());
            return;
        }
        boolean antiCheat = Bukkit.getCommandMap().getCommand("anticheat") != null;
        if (preset.group() == ReportGroup.CHEATING && antiCheat) {
            band(MenuLayout.RULES, 6, viewer.hasPermission("rainsanticheat.command"),
                    Icons.of(Material.COMMAND_BLOCK, "<yellow>Anti-cheat", "<gray>What the checks saw of them."),
                    "For whoever may use the anti-cheat",
                    click -> viewer.performCommand("anticheat " + report.subjectName()));
            return;
        }
        band(MenuLayout.RULES, 6, may(ModerationPermission.HISTORY), Icons.of(Material.BOOK, "<yellow>Their record",
                        "<gray>Every warning, mute and ban."),
                "For whoever may read a record",
                click -> new HistoryMenu(services(), viewer, this, report.subject(), report.subjectName()).open());
    }

    private void place(int column, ReportPlace where, String title, String what, boolean teleport) {
        if (where == null) {
            band(MenuLayout.LAND, column, Icons.of(Material.COMPASS, "<gray>" + title, "<gray>Not known for this report."));
            return;
        }
        band(MenuLayout.LAND, column, teleport, Icons.of(Material.COMPASS, "<yellow>" + title, "<gray>" + what,
                        "<white>" + where.describe(), "<dark_gray>Click to go there."),
                "For whoever may teleport",
                click -> {
                    World world = Bukkit.getWorld(where.world());
                    if (world == null) {
                        tell("moderation.report.world-gone", "world", where.world());
                        return;
                    }
                    viewer.closeInventory();
                    viewer.teleportAsync(new Location(world, where.x() + 0.5, where.y(), where.z() + 0.5));
                });
    }

    private void goToThem(Report report) {
        Player target = services().server().getPlayer(report.subject());
        if (target == null) {
            tell("moderation.not-here", "player", report.subjectName());
            return;
        }
        viewer.closeInventory();
        de.raindancer.core.platform.util.Scheduling.entity(services().plugin(), target, () -> {
            Location at = target.getLocation();
            de.raindancer.core.platform.util.Scheduling.entity(services().plugin(), viewer, () -> viewer.teleportAsync(at));
        });
    }

    /** Asks for the sentence the reporter will be shown, then closes it. */
    private void close(Report report, boolean dealtWith, boolean allowed) {
        if (!allowed) {
            tell("moderation.no-permission");
            return;
        }
        viewer.closeInventory();
        tell(dealtWith ? "moderation.report.type-what-you-did" : "moderation.report.type-why-not", "id", report.id());
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    boolean closed = dealtWith
                            ? services().reportService().resolve(reportId, viewer.getUniqueId(), viewer.getName(), typed)
                            : services().reportService().reject(reportId, viewer.getUniqueId(), viewer.getName(), typed);
                    tell(closed ? "moderation.report.closed" : "moderation.report.already-closed", "id", reportId);
                },
                () -> tell("moderation.nothing-typed"));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Top: how bad it looks, who it is about, who filed it.",
                "<gray>Everything to decide it is on this card.");
    }

    @Override
    public String describe() {
        return "one report: how bad, what, when, by whom, the evidence, the places, and the decision";
    }
}
