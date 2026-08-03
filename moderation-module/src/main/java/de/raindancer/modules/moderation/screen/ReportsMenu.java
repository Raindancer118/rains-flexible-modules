package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The queue.
 *
 * <h2>Live by default, everything on request</h2>
 * What a moderator coming on shift needs is what has not been dealt with. What they need ten minutes
 * later is often "what did somebody decide about that one", which is a different list — so both are
 * here, on a toolbar toggle that says which is showing.
 *
 * <p>Who has picked one up is on its button. That is the whole reason {@code CLAIMED} exists as a
 * state: without it, two moderators walk to the same grief and the second arrives to find nothing to do.
 */
public final class ReportsMenu extends ModerationList<Report> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private boolean showingEverything;

    public ReportsMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Reports");
    }

    @Override
    public String breadcrumb() {
        return "Reports";
    }

    @Override
    protected List<Report> entries() {
        return showingEverything ? services().reports().all() : services().reports().live();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB,
                showingEverything ? "<gray>Nothing has ever been reported"
                        : "<gray>Nothing waiting",
                "<gray>The queue is empty.");
    }

    @Override
    protected ItemStack icon(Report report) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + report.text());
        lore.add("");
        lore.add("<dark_gray>from " + report.reporterName() + ", "
                + Times.describe(Duration.between(report.at(), Instant.now())) + " ago");
        lore.add("<dark_gray>" + report.state().describe()
                + (report.handlerId().isPresent() ? " by " + report.handlerName() : ""));
        lore.add("");
        lore.add("<dark_gray>Click to open it.");

        return Icons.head(report.subject(), "<yellow>" + report.subjectName()
                + " <dark_gray>(" + report.id() + ")", lore);
    }

    @Override
    protected void onClick(Report report, InventoryClickEvent event) {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        new ReportMenu(services(), viewer, this, report.id()).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(showingEverything ? Material.BOOKSHELF : Material.BELL,
                        showingEverything ? "<yellow>Showing everything" : "<yellow>Showing what is live",
                        "<gray>" + services().reports().waitingCount() + " waiting, "
                                + services().reports().size() + " in all.",
                        "<dark_gray>Click to swap."),
                click -> {
                    showingEverything = !showingEverything;
                    refresh();
                });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Pick one up before you walk over, so nobody else does too.",
                "<gray>Closing one tells whoever filed it what you decided.");
    }

    @Override
    public String describe() {
        return "the report queue: what is waiting, and who has picked what up";
    }
}
