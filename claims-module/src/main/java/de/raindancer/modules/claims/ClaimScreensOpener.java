package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import org.bukkit.entity.Player;

/**
 * Opening a screen, as something the rest of the module can ask for without knowing what a screen is.
 *
 * <h2>Why not just call the menu classes</h2>
 * Because then every listener and every service would depend on the screens, and the screens depend on all of
 * them — which is the cycle that made the old plugin impossible to take apart. This is the one edge that breaks
 * it: services and listeners ask for a screen, and only the module's own wiring knows which class that is.
 *
 * <p>It is also what let the screens be rebuilt from scratch without touching a line of the logic underneath.
 */
public interface ClaimScreensOpener {

    /** Everything about one claim. */
    void claim(Player viewer, Claim claim);

    /** The player's own claims. */
    void list(Player viewer);

    /** The marking tool's own screen — shape, purpose, what has been clicked so far. */
    void selection(Player viewer);

    /** Choosing what a fence is made of. */
    void fenceMaterial(Player viewer, Claim claim);

    /** Stocking the pantry. */
    void pantry(Player viewer, Claim claim);

    /** Stocking the potions the granted effects drink. */
    void potionStore(Player viewer, Claim claim);

    /** The words somebody sees across the screen on arriving and leaving. */
    void titles(Player viewer, Claim claim);

    /** The server-wide administration screen. */
    void admin(Player viewer);

    /** The manual, opened and left in their inventory. */
    void manual(Player viewer);
}
