package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.model.Hunt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everybody who would be in the next hunt, and everybody on a side: a click puts them where they
 * belong — see {@link HubModel#sideClick}. The tools balance by rating, draw a Runner, or start over.
 */
public final class SidesEditorMenu extends PaginatedMenu<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;

    public SidesEditorMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Sides");
    }

    @Override
    public String breadcrumb() {
        return "Sides";
    }

    @Override
    protected List<UUID> entries() {
        ManhuntServices live = pages.services();
        Set<UUID> everybody = new LinkedHashSet<>(live.desk().present());
        everybody.addAll(live.teams().everybody());
        List<UUID> sorted = new ArrayList<>(everybody);
        sorted.sort(Comparator.comparing((UUID id) -> live.teams().isRunner(id) ? 0 : live.teams().isHunter(id) ? 1 : 2)
                .thenComparing(live.desk()::nameOf));
        return sorted;
    }

    @Override
    protected ItemStack icon(UUID id) {
        ManhuntServices live = pages.services();
        Hunt hunt = live.mode().current().orElse(null);
        boolean runner = hunt != null ? hunt.isRunner(id) : live.teams().isRunner(id);
        boolean hunter = hunt != null ? hunt.isHunter(id) : live.teams().isHunter(id);
        String side = runner ? "<green>Runner" : hunter ? "<red>Hunter" : "<gray>No side — hunts at the start";
        String here = live.desk().present().contains(id) ? "<gray>Here." : "<dark_gray>Not here — not in the next hunt.";
        return Icons.head(id, (runner ? "<green>" : hunter ? "<red>" : "<white>") + MINI.escapeTags(live.desk().nameOf(id)),
                side, here, "<gray>Rating " + Math.round(live.chronicle().stats().rating(id)),
                "<dark_gray>Left: Runner · Right: Hunter · Shift: no side");
    }

    @Override
    protected void onClick(UUID id, InventoryClickEvent event) {
        pages.run(viewer, HubModel.sideClick(pages.services().desk().nameOf(id), event.isRightClick(),
                event.isShiftClick()));
        refresh();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody here", "<dark_gray>Players in the lobby show up here.");
    }

    @Override
    protected void render() {
        super.render();
        boolean on = pages.services().mode().isRunning();
        String fixed = "Sides do not move during a hunt";
        toolbar(2, !on, Icons.of(Material.COMPARATOR, "<yellow>Balance by rating"), fixed,
                click -> act("manhunt balance"));
        toolbar(3, !on, Icons.of(Material.ENDER_PEARL, "<aqua>A random Runner"), fixed,
                click -> act("manhunt random 1"));
        toolbar(5, !on, Icons.of(Material.LAVA_BUCKET, "<red>Clear both sides"), fixed,
                click -> act("manhunt reset"));
        toolbar(6, Icons.of(Material.WRITABLE_BOOK, "<gold>Pre-flight check"),
                click -> pages.open(viewer, ManhuntServices.Page.PREFLIGHT, this));
    }

    private void act(String command) {
        pages.run(viewer, command);
        refresh();
    }
}
