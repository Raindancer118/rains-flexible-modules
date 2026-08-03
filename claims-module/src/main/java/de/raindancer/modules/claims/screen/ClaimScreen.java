package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * The base every claim screen sits on.
 *
 * <h2>What it is not</h2>
 * Not a menu framework. There is exactly one of those and it is Core's {@link Menu} — the version this replaces
 * had its own, which is why the same plugin looked like five plugins and a fix to one screen stayed in that one
 * screen. This adds only the two things every claim screen needs and nothing else has: the claim it is about,
 * and the services to do something to it.
 *
 * <h2>The grammar these screens follow</h2>
 * Core's layout gives three semantic bands, and the claim screens use them consistently — which is the whole
 * answer to "the menu feels cluttered":
 *
 * <ul>
 *   <li><b>{@code WHO}</b> — people. Members, the public grant, bans.</li>
 *   <li><b>{@code RULES}</b> — what may happen here. Flags, features.</li>
 *   <li><b>{@code LAND}</b> — the ground itself. Shape, height, fence, name and icon.</li>
 *   <li><b>the toolbar</b> — what the claim <em>has</em>: its bank, its pantry, its perks.</li>
 *   <li><b>the danger slot</b> — the one irreversible thing, and only ever a confirmation.</li>
 * </ul>
 *
 * <p>A screen that wants a fourth category does not get one. That constraint is the feature: five bands of two
 * buttons is the cluttered menu again with extra steps.
 */
public abstract class ClaimScreen extends Menu {

    private final ClaimServices services;
    private final Claim claim;

    /**
     * @param claim the claim this screen is about, or null on the screens that are about all of them
     */
    protected ClaimScreen(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    /** @param rows three for a dialog, which gets Back and Close only; six for a page */
    protected ClaimScreen(ClaimServices services, Player viewer, Claim claim, Menu parent, int rows) {
        super(viewer, services.brand(), parent, rows);
        this.services = services;
        this.claim = claim;
    }

    protected ClaimServices services() {
        return services;
    }

    /** The claim this screen is about. Null on the screens that are about all of them. */
    protected Claim claim() {
        return claim;
    }

    /** Whether the viewer may change the claim in this way — for the greyed-out form of a button. */
    protected boolean may(ClaimAdminPermission permission) {
        return claim != null && services.rights().canManage(claim, viewer, permission);
    }

    /**
     * Says something to the viewer and closes nothing.
     *
     * <p>Screens answer in chat rather than by silently doing nothing: a button that refuses without saying so
     * is a button a player presses four more times.
     */
    protected void tell(String key, Object... values) {
        services.messages().send(viewer, key, values);
    }

    /** Re-renders after a change, keeping the window open — which is what a toggle needs. */
    protected void changed(InventoryClickEvent event) {
        refresh();
    }
}
