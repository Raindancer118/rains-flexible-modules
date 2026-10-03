package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.stats.HuntRecord;
import de.raindancer.modules.manhunt.stats.HuntSummary;
import de.raindancer.modules.manhunt.stats.Milestone;
import de.raindancer.modules.manhunt.stats.TimelineEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One hunt, moment by moment: every event on its timeline as an icon, the MVPs and the splits in the
 * toolbar, and every player's numbers a click away.
 */
public final class SummaryMenu extends PaginatedMenu<TimelineEvent> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;
    private final HuntRecord record;
    private final HuntSummary summary;

    public SummaryMenu(Pages pages, Player viewer, Menu parent, HuntRecord record) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
        this.record = record;
        this.summary = HuntSummary.of(record);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Hunt #" + record.number());
    }

    @Override
    public String breadcrumb() {
        return "Hunt #" + record.number();
    }

    @Override
    protected List<TimelineEvent> entries() {
        return record.events();
    }

    @Override
    protected ItemStack icon(TimelineEvent event) {
        String at = "<gray>" + HuntSummary.clock(event.atMillis()) + " ";
        String who = event.whoName() == null ? "somebody" : MINI.escapeTags(event.whoName());
        String by = event.otherName() == null ? null : MINI.escapeTags(event.otherName());
        return switch (event.kind()) {
            case STARTED -> Icons.of(Material.LIME_CONCRETE, at + "<green>The hunt began",
                    "<gray>" + event.detail() + " Runner(s).");
            case CAUGHT -> Icons.of(Material.SKELETON_SKULL, at + "<red>" + who + " was caught",
                    by == null ? "<gray>By the world." : "<gray>By <white>" + by + "<gray>.");
            case LIFE_LOST -> Icons.of(Material.RED_DYE, at + "<yellow>" + who + " lost a life",
                    "<gray>" + event.detail() + " left." + (by == null ? "" : " By " + by + "."));
            case CAUGHT_AWAY -> Icons.of(Material.CLOCK, at + "<red>" + who + " stayed away too long");
            case HUNTER_DIED -> Icons.of(Material.IRON_SWORD, at + "<gray>" + who + " (Hunter) died",
                    by == null ? "<gray>By the world." : "<gray>By <white>" + by + "<gray>.");
            case LEFT -> Icons.of(Material.OAK_DOOR, at + "<gray>" + who + " left the hunt");
            case JOINED -> Icons.of(Material.PLAYER_HEAD, at + "<aqua>" + who + " joined as a " + event.detail());
            case SIDE_CHANGED -> Icons.of(Material.COMPARATOR, at + "<aqua>" + who + " became a " + event.detail());
            case MILESTONE -> Icons.of(Material.NETHER_STAR, at + "<gold>" + Milestone.byId(event.detail())
                    .map(m -> pages.services().messages().raw("manhunt.milestone-name." + m.id())).orElse(event.detail()),
                    "<gray>Reached by <white>" + (event.whoName() == null ? "—" : who) + "<gray>.");
            case FINISHED -> Icons.of(Material.BLACK_BANNER, at + "<white>The hunt ended", "<gray>" + event.detail());
        };
    }

    @Override
    protected void onClick(TimelineEvent event, InventoryClickEvent click) {
        if (event.who() != null) {
            pages.stats(viewer, event.who(), this);
        }
    }

    @Override
    protected void render() {
        super.render();
        List<String> splits = new ArrayList<>();
        for (HuntSummary.Split split : summary.splits()) {
            splits.add("<gray>" + HuntSummary.clock(split.atMillis()) + " <white>"
                    + pages.services().messages().raw("manhunt.milestone-name." + split.milestone().id()));
        }
        if (splits.isEmpty()) {
            splits.add("<dark_gray>No milestone reached.");
        }
        toolbar(2, Icons.of(Material.CLOCK, "<aqua>Splits", splits), click -> { });
        toolbar(3, Icons.of(Material.IRON_SWORD, "<red>Hunter MVP",
                summary.hunterMvp().map(m -> "<white>" + MINI.escapeTags(m.name()) + " <gray>(" + m.catches() + " catch(es))")
                        .orElse("<dark_gray>Nobody caught anybody.")), click -> summary.hunterMvp()
                .ifPresent(m -> pages.stats(viewer, m.id(), this)));
        toolbar(5, Icons.of(Material.FEATHER, "<green>Runner MVP",
                summary.runnerMvp().map(m -> "<white>" + MINI.escapeTags(m.name()) + " <gray>(ran "
                        + HuntSummary.clock(m.survivedMillis()) + ")").orElse("<dark_gray>—")),
                click -> summary.runnerMvp().ifPresent(m -> pages.stats(viewer, m.id(), this)));
        toolbar(6, Icons.of(Material.LEATHER_BOOTS, "<gold>Explorer",
                summary.explorer().map(m -> "<white>" + MINI.escapeTags(m.name()) + " <gray>(" + Math.round(m.distance())
                        + " blocks)").orElse("<dark_gray>—")),
                click -> summary.explorer().ifPresent(m -> pages.stats(viewer, m.id(), this)));
    }
}
