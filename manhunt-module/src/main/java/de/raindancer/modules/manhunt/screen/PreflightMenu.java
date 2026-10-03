package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.setup.Preflight;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Everything worth knowing before the start, a check per icon, each fixed by clicking it — and the
 * start itself, the moment nothing stands in its way.
 */
public final class PreflightMenu extends PaginatedMenu<Preflight.Check> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Map<String, String> TITLES = Map.of(
            "no-lobby", "No speedrun lobby", "hunt-running", "A hunt is on", "too-few", "Too few players",
            "no-runner", "Nobody is running", "no-hunter", "Nobody is hunting", "goal-unknown", "A goal nobody can reach",
            "no-goal", "No goal", "runner-away", "A Runner is not here",
            "whitelist-will-close", "The server will close", "lopsided", "Lopsided sides");

    private static final Map<Preflight.Fix, String> FIXES = Map.of(
            Preflight.Fix.PICK_RANDOM_RUNNER, "Pick a Runner at random",
            Preflight.Fix.AUTO_BALANCE, "Balance the sides by rating",
            Preflight.Fix.OPEN_SIDES, "Open the sides",
            Preflight.Fix.REMOVE_GOAL, "Play without a goal",
            Preflight.Fix.CHOOSE_GOAL, "Pick a goal",
            Preflight.Fix.KEEP_DOOR_OPEN, "Keep the server open");

    private final Pages pages;

    public PreflightMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Before the start");
    }

    @Override
    public String breadcrumb() {
        return "Pre-flight";
    }

    @Override
    protected List<Preflight.Check> entries() {
        return pages.services().desk().preflight();
    }

    @Override
    protected ItemStack icon(Preflight.Check check) {
        Material material = switch (check.severity()) {
            case BLOCKER -> Material.RED_CONCRETE;
            case WARNING -> Material.YELLOW_CONCRETE;
            case INFO -> Material.LIGHT_BLUE_CONCRETE;
        };
        String colour = switch (check.severity()) {
            case BLOCKER -> "<red>";
            case WARNING -> "<yellow>";
            case INFO -> "<aqua>";
        };
        String detail = check.placeholder().isEmpty() ? "" : MINI.escapeTags(check.placeholder());
        String fix = FIXES.get(check.fix());
        return Icons.of(material, colour + TITLES.getOrDefault(check.key(), check.key()),
                check.severity() == Preflight.Severity.BLOCKER ? "<gray>The start would be refused." : "<gray>Worth knowing.",
                detail.isEmpty() ? "<dark_gray>—" : "<white>" + detail,
                fix == null ? "<dark_gray>Nothing to click — wait or talk." : "<green>Click: " + fix);
    }

    @Override
    protected void onClick(Preflight.Check check, InventoryClickEvent event) {
        HubModel.Click click = HubModel.fix(check.fix());
        if (click == null) {
            return;
        }
        if (click.page() != null) {
            pages.open(viewer, click.page(), this);
            return;
        }
        pages.run(viewer, click.command());
        refresh();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.LIME_CONCRETE, "<green>All clear", "<gray>Nothing stands in the way.",
                "<green>Click to start the hunt.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        start();
    }

    @Override
    protected void render() {
        super.render();
        boolean ready = Preflight.ready(entries());
        toolbar(4, ready, Icons.of(Material.LIME_CONCRETE, "<green>Start the hunt"), "Fix the red ones first",
                click -> start());
    }

    private void start() {
        viewer.closeInventory();
        pages.run(viewer, "manhunt start");
    }
}
