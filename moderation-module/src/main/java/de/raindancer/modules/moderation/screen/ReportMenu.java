package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One report, and what may be done with it.
 *
 * <h2>Why closing it asks for a sentence</h2>
 * Because the outcome is what the player who filed it is told, and "resolved" tells them nothing. The
 * difference between a report system people use and one they stop using is entirely whether anything
 * comes back — so the moderator types a line, and the reporter gets it.
 */
public final class ReportMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String reportId;

    public ReportMenu(ModerationServices services, Player viewer, Menu parent, String reportId) {
        super(services, viewer, parent);
        this.reportId = reportId;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Report <white>" + reportId);
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
        boolean allowed = may(ModerationPermission.REPORTS);

        band(MenuLayout.WHO, 2, Icons.head(report.subject(),
                "<white>" + report.subjectName(),
                "<gray>Reported by <white>" + report.reporterName() + "</white>.",
                "<dark_gray>" + Times.describe(Duration.between(report.at(), Instant.now())) + " ago"));

        band(MenuLayout.WHO, 4, Icons.of(report.state().icon(), "<yellow>" + report.state().describe(),
                whatItSays(report)));

        band(MenuLayout.WHO, 6, allowed,
                Icons.of(Material.SPYGLASS, "<yellow>Open their page",
                        "<gray>Everything about " + report.subjectName() + "."),
                "For whoever may read reports",
                click -> new PlayerMenu(services(), viewer, this, report.subject(), report.subjectName()).open());

        if (report.isClosed()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.LIME_DYE, "<green>Dealt with",
                    "<gray>" + report.outcome(),
                    "<dark_gray>by " + report.handlerName()));
            return;
        }

        band(MenuLayout.RULES, 2, allowed,
                report.isWaiting()
                        ? Icons.of(Material.SPYGLASS, "<yellow>Pick it up",
                        "<gray>Tells the other staff you are on it.")
                        : Icons.of(Material.SPYGLASS, "<yellow>Hand it back",
                        "<gray>" + report.handlerName() + " has it.",
                        "<dark_gray>Puts it back in the queue."),
                "For whoever may read reports",
                click -> {
                    if (report.isWaiting()) {
                        services().reportService().claim(reportId, viewer.getUniqueId(),
                                viewer.getName());
                    } else {
                        services().reportService().release(reportId);
                    }
                    changed();
                });

        band(MenuLayout.RULES, 4, allowed,
                Icons.of(Material.LIME_CONCRETE, "<green>Dealt with",
                        "<gray>Something was done about it.",
                        "<dark_gray>You will be asked what, and they are told."),
                "For whoever may read reports",
                click -> close(report, true));

        band(MenuLayout.RULES, 6, allowed,
                Icons.of(Material.GRAY_CONCRETE, "<gray>Nothing in it",
                        "<gray>Looked at, and there was nothing to do.",
                        "<dark_gray>You will be asked why, and they are told."),
                "For whoever may read reports",
                click -> close(report, false));
    }

    private List<String> whatItSays(Report report) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + report.text());
        if (report.handlerId().isPresent()) {
            lore.add("");
            lore.add("<dark_gray>with " + report.handlerName());
        }
        if (report.isClosed()) {
            lore.add("");
            lore.add("<dark_gray>" + report.outcome());
        }
        return lore;
    }

    /** Asks for the sentence the reporter will be shown, then closes it. */
    private void close(Report report, boolean dealtWith) {
        viewer.closeInventory();
        tell(dealtWith ? "moderation.report.type-what-you-did"
                : "moderation.report.type-why-not", "id", report.id());
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    boolean closed = dealtWith
                            ? services().reportService().resolve(reportId, viewer.getUniqueId(),
                            viewer.getName(), typed)
                            : services().reportService().reject(reportId, viewer.getUniqueId(),
                            viewer.getName(), typed);
                    tell(closed ? "moderation.report.closed" : "moderation.report.already-closed",
                            "id", reportId);
                },
                () -> tell("moderation.nothing-typed"));
    }

    @Override
    public String describe() {
        return "one report: who filed it, what it says, and what was decided";
    }
}
