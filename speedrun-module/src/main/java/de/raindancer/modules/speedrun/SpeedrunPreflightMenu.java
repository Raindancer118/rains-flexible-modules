package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Before the start: every check, green or red, each red one fixed with a click — and the start
 * button, which works the moment nothing red is left.
 */
public final class SpeedrunPreflightMenu extends PaginatedMenu<SpeedrunPreflight.Check> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final SpeedrunActions actions;

    public SpeedrunPreflightMenu(SpeedrunLobby lobby, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.actions = new SpeedrunActions(lobby);
    }

    private SpeedrunPreflight now() {
        return SpeedrunPreflight.of(lobby, lobby.presentInLobbyWorld());
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
    protected List<SpeedrunPreflight.Check> entries() {
        return now().checks();
    }

    @Override
    protected void decorate() {
        SpeedrunPreflight preflight = now();
        boolean mayStart = SpeedrunAccess.START.allows(lobby, viewer);
        set(MenuLayout.HEADER_SUBJECT, Icons.of(preflight.clear() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                preflight.clear() ? "<green>Ready to start" : "<red>Not ready yet",
                preflight.clear() ? "<gray>Everything a run needs is in place."
                        : "<gray>Click a red check to fix it."));
        toolbar(4, preflight.clear() && mayStart && lobby.state() == SpeedrunLobbyState.READY,
                Icons.of(Material.LIME_CONCRETE, "<green>Start the countdown",
                        "<gray>Everybody racing in the lobby world races.",
                        "<gray>Their hands are emptied for it."),
                !mayStart ? "Only staff can start a run here" : "Fix the red checks first",
                SpeedrunAccess.START.guard(lobby, viewer, click -> {
                    viewer.closeInventory();
                    actions.start(viewer);
                }));
        super.decorate();
    }

    @Override
    protected ItemStack icon(SpeedrunPreflight.Check check) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + SpeedrunScreens.text(check.detail()));
        if (!check.ok()) {
            lore.add(check.blocking() ? "<red>Stops the start." : "<yellow>A warning only.");
            if (actions.mayFix(check, viewer)) {
                lore.add("");
                lore.add("<green>Click: " + SpeedrunScreens.text(check.fix() == SpeedrunPreflight.Fix.MODE
                        ? check.modeFix().label() : SpeedrunActions.fixLabel(check.fix())));
            }
        }
        Material icon = check.ok() ? Material.LIME_DYE : check.blocking() ? Material.RED_DYE : Material.YELLOW_DYE;
        return Icons.of(icon, (check.ok() ? "<green>" : check.blocking() ? "<red>" : "<yellow>")
                + SpeedrunScreens.text(check.label()), lore);
    }

    @Override
    protected void onClick(SpeedrunPreflight.Check check, InventoryClickEvent event) {
        if (check.ok() || !actions.mayFix(check, viewer)) {
            return;   // asked at the click: the page may have been open since a permission was taken
        }
        if (check.fix() == SpeedrunPreflight.Fix.MODE_SETUP) {
            lobby.mode().flatMap(SpeedrunMode::setup).ifPresent(setup -> setup.open(viewer, this));
            return;
        }
        actions.applyCheck(check, viewer, this);
        if (check.fix() != SpeedrunPreflight.Fix.RESET && check.fix() != SpeedrunPreflight.Fix.MODE) {
            refresh();
        }
    }
}
