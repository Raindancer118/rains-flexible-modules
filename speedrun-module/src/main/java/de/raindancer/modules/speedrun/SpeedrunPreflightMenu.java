package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.core.ui.checklist.ChecklistMenu;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Before the start: Core's checklist page over the lobby's checks and the game's — every check green
 * or red, each red one fixed with a click by somebody allowed to, and the start button, which lights
 * up the moment nothing red is left.
 *
 * <p>Only two fixes behave differently from a page: a reset asks on a confirmation page that comes
 * back here, and the game's own setup page opens with this one as its parent.
 */
public final class SpeedrunPreflightMenu extends ChecklistMenu {

    private final SpeedrunActions actions;

    public SpeedrunPreflightMenu(SpeedrunLobby lobby, Player viewer, Menu parent) {
        this(lobby, new SpeedrunActions(lobby), viewer, parent);
    }

    private SpeedrunPreflightMenu(SpeedrunLobby lobby, SpeedrunActions actions, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent, () -> actions.checklist(viewer),
                "Start the countdown", clicker -> {
                    // Asked again at the click, as every button here is.
                    if (SpeedrunAccess.START.allows(lobby, clicker)) {
                        clicker.closeInventory();
                        actions.start(clicker);
                    }
                });
        this.actions = actions;
    }

    @Override
    protected void onClick(Checklist.Check check, InventoryClickEvent event) {
        SpeedrunPreflight.Fix fix = SpeedrunPreflight.fixOf(check).orElse(SpeedrunPreflight.Fix.NONE);
        if (fix == SpeedrunPreflight.Fix.RESET || fix == SpeedrunPreflight.Fix.MODE_SETUP) {
            // Asked at the click: the page may have been open since a permission was taken.
            if (check.fixIfAny().filter(offered -> offered.allowedFor(viewer)).isPresent()) {
                actions.applyIfAllowed(fix, viewer, this);
            }
            return;
        }
        super.onClick(check, event);
    }
}
