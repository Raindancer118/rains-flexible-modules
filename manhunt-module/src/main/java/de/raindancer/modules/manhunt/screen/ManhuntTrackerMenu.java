package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.tracker.TrackerCompassService;
import de.raindancer.modules.manhunt.tracker.TrackerCompassService.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Who a Hunter's compass follows, picked from a list rather than cycled to — opened by sneaking and
 * right-clicking the compass. The nearest Runner, every Runner, and with the team compass on every
 * teammate. The list is read fresh on every render, so a Runner caught while it is open drops out.
 */
public final class ManhuntTrackerMenu extends PaginatedMenu<Target> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TrackerCompassService tracker;
    private final String title;

    public ManhuntTrackerMenu(TrackerCompassService tracker, Brand brand, String title, Player viewer) {
        super(viewer, brand, null);
        this.tracker = tracker;
        this.title = title == null || title.isBlank() ? "Track whom?" : title;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + MINI.escapeTags(title));
    }

    @Override
    public String breadcrumb() {
        return "Compass";
    }

    @Override
    protected List<Target> entries() {
        return tracker.targetsFor(viewer);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody to follow", "<dark_gray>The hunt is not on.");
    }

    @Override
    protected ItemStack icon(Target target) {
        List<String> lore = new ArrayList<>();
        String name = MINI.escapeTags(target.name());
        ItemStack icon;
        if (target.following().isNearest()) {
            lore.add("<gray>The needle swings to the closest Runner.");
            lore.add(footer(target));
            icon = Icons.of(Material.COMPASS, "<gold>" + name, lore);
        } else if (target.teammate()) {
            lore.add("<gray>Your teammate.");
            lore.add(footer(target));
            icon = Icons.head(target.following().runner(), "<red>" + name, lore);
        } else {
            lore.add("<gray>A Runner.");
            lore.add(footer(target));
            icon = Icons.head(target.following().runner(), "<green>" + name, lore);
        }
        return icon;
    }

    private static String footer(Target target) {
        return target.current() ? "<yellow>Following now." : "<dark_gray>Click to follow.";
    }

    @Override
    protected void onClick(Target target, InventoryClickEvent event) {
        tracker.pick(viewer, target.following());
        viewer.closeInventory();
    }
}
