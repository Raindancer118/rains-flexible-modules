package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.stats.HuntRecord;
import de.raindancer.modules.manhunt.stats.HuntSummary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** The hunts this server kept, newest first — each one opens its summary. */
public final class HistoryMenu extends PaginatedMenu<HuntRecord> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;

    public HistoryMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Past hunts");
    }

    @Override
    public String breadcrumb() {
        return "Past hunts";
    }

    @Override
    protected List<HuntRecord> entries() {
        return pages.services().chronicle().history().all();
    }

    @Override
    protected ItemStack icon(HuntRecord record) {
        Material banner = switch (record.winner()) {
            case RUNNERS -> Material.LIME_BANNER;
            case HUNTERS -> Material.RED_BANNER;
            case NOBODY -> Material.GRAY_BANNER;
        };
        String winner = switch (record.winner()) {
            case RUNNERS -> "<green>The Runners won";
            case HUNTERS -> "<red>The Hunters won";
            case NOBODY -> "<gray>Won by nobody";
        };
        long runners = record.players().stream().filter(p -> p.runner()).count();
        return Icons.of(banner, "<gold>Hunt #" + record.number(), winner,
                "<gray>" + HuntSummary.clock(record.durationMillis()) + ", " + runners + " Runner(s) against "
                        + (record.players().size() - runners) + ".", "<dark_gray>Click for the whole hunt.");
    }

    @Override
    protected void onClick(HuntRecord record, InventoryClickEvent event) {
        new SummaryMenu(pages, viewer, this, record).open();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No hunts kept yet", "<dark_gray>They show up here once played.");
    }
}
