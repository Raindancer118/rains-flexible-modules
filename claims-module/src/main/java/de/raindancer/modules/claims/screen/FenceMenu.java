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

import java.util.List;

/** A real fence along the border: whether it stands, what it is made of, and putting it back. */
public final class FenceMenu extends ClaimScreen {

    public FenceMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Fence");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean allowed = may(ClaimAdminPermission.MANAGE_SHAPE);
        boolean standing = claim.fence().enabled();

        band(MenuLayout.WHO, 2, allowed,
                Icons.of(standing ? Material.LIME_DYE : Material.GRAY_DYE,
                        standing ? "<green>Standing" : "<gray>Not built",
                        "<gray>" + claim.fence().standingBlocks() + " block(s) placed",
                        "",
                        "<dark_gray>click to " + (standing ? "take it down" : "put it up")),
                "The owner's to change",
                click -> {
                    if (standing) {
                        services().fences().tearDown(claim, services().config().fenceRefundToBank());
                    } else {
                        services().fences().build(claim, viewer);
                    }
                    services().claimService().saveAsync(claim);
                    refresh();
                });

        band(MenuLayout.WHO, 4, allowed,
                Icons.of(claim.fence().material(), "<white>Made of",
                        "<gray>" + claim.fence().material().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', ' '),
                        "",
                        "<dark_gray>click to choose another"),
                "The owner's to change",
                click -> new de.raindancer.core.ui.choose.ItemChooser(viewer, services().brand(), this,
                        "Fence material",
                        chosen -> {
                            services().fences().changeMaterial(claim, chosen, viewer);
                            services().claimService().saveAsync(claim);
                            new FenceMenu(services(), viewer, claim, parent()).open();
                        }).open());

        band(MenuLayout.WHO, 6, Icons.of(Material.OAK_FENCE_GATE, "<white>Gaps you have made",
                claim.fence().suppressed().size() + " left open",
                "<gray>Break a fence block to leave a gap;",
                "<gray>the plugin will not fill it in again."));

        toolbar(4, Icons.of(Material.SPYGLASS, "<white>Show me the border",
                        "<gray>Outline it where you are standing."),
                click -> {
                    viewer.closeInventory();
                    services().visualizer().showClaim(viewer, claim,
                            services().config().visualDurationSeconds());
                });
    }
}
