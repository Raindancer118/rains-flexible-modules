package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The seventeen permissions as a grid, shared by the two screens that show them.
 *
 * <h2>Why one class and not two</h2>
 * Because "what may this trusted player do" and "what may a visitor do" are the same seventeen questions with a
 * different place to store the answer. Written twice they drift: one gets a new permission and the other does
 * not, and the one that does not is the one somebody notices six months later when a visitor can do something a
 * trusted player cannot.
 *
 * <p>So the grid, the icons, the on/off wording and the greying are here, and a subclass says only where the
 * answer lives. It also fixes the layout question the old screens got wrong differently from each other: a set of
 * seventeen equal things is a <em>grid</em>, drawn with {@code cell}, not a set of bands — bands centre what they
 * hold, so a seventeen-item band re-centred itself every time one was toggled off.
 */
abstract class PermissionGrid extends ClaimScreen {

    protected PermissionGrid(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    /** Whether the subject currently holds this. */
    protected abstract boolean holds(LandAction action);

    /** Records a change. Called only when {@link #mayChange(LandAction)} said yes. */
    protected abstract void set(LandAction action, boolean allowed);

    /** Whether this viewer may change this particular one. */
    protected abstract boolean mayChange(LandAction action);

    /** Shown on a greyed button, saying whose it is instead. */
    protected abstract String refusal(LandAction action);

    /**
     * Told after a change is recorded and saved. Nothing by default: {@link PublicPermissionsMenu} answers
     * for everybody, and there is no one person to tell.
     *
     * <p>A subclass with a single subject overrides this to let them know — being handed something you may
     * now do, or having it taken back, is not something a person otherwise finds out except by trying it.
     */
    protected void afterChange(LandAction action, boolean allowed) {
    }

    @Override
    protected void render() {
        LandAction[] actions = LandAction.values();
        for (int at = 0; at < actions.length; at++) {
            LandAction action = actions[at];
            int row = at / 9;
            int column = at % 9;
            ItemStack icon = iconFor(action);
            if (mayChange(action)) {
                cell(row, column, icon, click -> {
                    boolean allowed = !holds(action);
                    set(action, allowed);
                    claim().markDirty();
                    services().claimService().saveAsync(claim());
                    afterChange(action, allowed);
                    refresh();
                });
            } else {
                cell(row, column, Icons.locked(icon, refusal(action)), click -> {
                });
            }
        }
    }

    private ItemStack iconFor(LandAction action) {
        boolean on = holds(action);
        String name = (on ? "<green>" : "<red>") + services().messages().raw(action.nameKey());
        return Icons.of(action.icon(), name,
                "<gray>" + services().messages().raw(action.descriptionKey()),
                "",
                on ? "<green>✔ allowed" : "<red>✘ not allowed",
                "<dark_gray>click to change");
    }
}
