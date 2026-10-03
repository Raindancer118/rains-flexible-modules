package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.setup.Goals;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** What the Runners race for: the goals worth one click, and none at all. */
public final class GoalMenu extends PaginatedMenu<Goals.Goal> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;

    public GoalMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>What do the Runners race for?");
    }

    @Override
    public String breadcrumb() {
        return "Goal";
    }

    @Override
    protected List<Goals.Goal> entries() {
        return Goals.all();
    }

    @Override
    protected ItemStack icon(Goals.Goal goal) {
        boolean now = goal.key().equals(pages.services().desk().goal());
        return Icons.of(ManhuntHubMenu.material(goal.icon()), (now ? "<yellow>" : "<gold>") + goal.label(),
                "<gray>" + goal.length() + ".", now ? "<yellow>The goal now." : "<dark_gray>Click to race for this.");
    }

    @Override
    protected void onClick(Goals.Goal goal, InventoryClickEvent event) {
        pages.run(viewer, "manhunt goal set " + goal.key());
        refresh();
    }

    @Override
    protected void render() {
        super.render();
        boolean none = pages.services().desk().goal().isEmpty();
        toolbar(4, Icons.of(Material.BARRIER, none ? "<yellow>No goal — now" : "<red>No goal",
                        "<gray>The hunt ends when the last", "<gray>Runner is caught, or by reset."),
                click -> {
                    pages.run(viewer, "manhunt goal remove");
                    refresh();
                });
    }
}
