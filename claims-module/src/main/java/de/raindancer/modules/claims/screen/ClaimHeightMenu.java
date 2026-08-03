package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * How deep and how high the claim reaches, without redrawing it.
 *
 * <p>Separate from the border for a practical reason: somebody who wants their claim to reach bedrock should not
 * have to walk the outline again with the tool. That was the old behaviour and it is why most claims on a server
 * stop a few blocks below the surface — nobody redraws a claim to fix the depth.
 */
public final class ClaimHeightMenu extends ClaimScreen {

    /** How much one click moves the ceiling or the floor. Sixteen: a chunk-tall step, and few clicks to bedrock. */
    private static final int STEP = 16;

    public ClaimHeightMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("How deep and how high");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean allowed = may(ClaimAdminPermission.MANAGE_SHAPE);
        int min = claim.shape().minY();
        int max = claim.shape().maxY();
        // Read from the world rather than assumed: a world with a custom height is not -64 to 320, and a
        // claim clamped to the wrong numbers is one that stops short of bedrock for ever.
        org.bukkit.World world = services().server().getWorld(claim.worldId());
        int floor = world == null ? -64 : world.getMinHeight();
        int ceiling = world == null ? 319 : world.getMaxHeight() - 1;

        band(MenuLayout.WHO, 2, allowed, Icons.of(Material.NETHERRACK, "<green>Deeper",
                        "<gray>Now reaches down to <white>y " + min,
                        "<dark_gray>−" + STEP + " blocks, floor is y " + floor),
                "The owner's to change",
                click -> move(Math.max(floor, min - STEP), max));

        band(MenuLayout.WHO, 3, allowed, Icons.of(Material.DIRT, "<gray>Shallower",
                        "<gray>Now reaches down to <white>y " + min,
                        "<dark_gray>+" + STEP + " blocks"),
                "The owner's to change",
                click -> move(Math.min(max - 1, min + STEP), max));

        band(MenuLayout.WHO, 5, allowed, Icons.of(Material.GLASS, "<gray>Lower ceiling",
                        "<gray>Now reaches up to <white>y " + max,
                        "<dark_gray>−" + STEP + " blocks"),
                "The owner's to change",
                click -> move(min, Math.max(min + 1, max - STEP)));

        band(MenuLayout.WHO, 6, allowed, Icons.of(Material.LIGHT_BLUE_STAINED_GLASS, "<green>Higher ceiling",
                        "<gray>Now reaches up to <white>y " + max,
                        "<dark_gray>+" + STEP + " blocks, sky is y " + ceiling),
                "The owner's to change",
                click -> move(min, Math.min(ceiling, max + STEP)));

        toolbar(2, Icons.of(Material.BEDROCK, "<white>All the way",
                        "<gray>Bedrock to the build limit.",
                        "<dark_gray>the usual choice, and the one nobody found before"),
                click -> {
                    if (allowed) {
                        move(floor, ceiling);
                    } else {
                        tell("error.no-claim-permission");
                    }
                });

        toolbar(6, Icons.of(Material.SPYGLASS, "<white>Show me the border",
                        "<gray>Outline it where you are standing."),
                click -> {
                    viewer.closeInventory();
                    services().visualizer().showClaim(viewer, claim,
                            services().config().visualDurationSeconds());
                });
    }

    /** Applies a new vertical range and says what it became, so the numbers on screen are never stale. */
    private void move(int min, int max) {
        if (max - min + 1 < services().config().minClaimHeight()) {
            tell("error.claim-too-short", "minimum", String.valueOf(services().config().minClaimHeight()));
            return;
        }
        claim().verticalRange(min, max);
        services().claimService().saveAsync(claim());
        tell("claim.height-changed", "claim", claim().name(),
                "min-y", String.valueOf(min), "max-y", String.valueOf(max));
        refresh();
    }
}
