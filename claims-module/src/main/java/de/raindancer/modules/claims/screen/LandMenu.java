package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/** The ground itself: its outline, its depth, its fence and what it is called. */
public final class LandMenu extends ClaimScreen {

    public LandMenu(ClaimServices services, org.bukkit.entity.Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("The land — " + claim().name());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>The shape of the claim and how it presents itself.",
                "",
                "<white>Redraw</white> <dark_gray>·</dark_gray> <gray>a new outline, with the tool",
                "<white>Depth</white> <dark_gray>·</dark_gray> <gray>how far up and down it reaches",
                "<white>Fence</white> <dark_gray>·</dark_gray> <gray>a real one along the border",
                "<white>Name and icon</white> <dark_gray>·</dark_gray> <gray>how it looks in a list");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        band(MenuLayout.LAND, 2, may(ClaimAdminPermission.MANAGE_SHAPE),
                Icons.of(Material.STICK, "<green>Redraw the border",
                        "<gray>Mark a new outline out with the tool.",
                        "<dark_gray>" + claim.shape().areaBlocks() + " blocks"),
                "The owner's to change",
                click -> {
                    viewer.closeInventory();
                    services().selectionFlow().begin(viewer,
                            de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                            de.raindancer.modules.claims.selection.Selection.Purpose.RESIZE_CLAIM,
                            null, claim, null);
                });

        band(MenuLayout.LAND, 4, may(ClaimAdminPermission.MANAGE_SHAPE),
                Icons.of(Material.LADDER, "<green>How deep and how high",
                        "<gray>Change the height without redrawing.",
                        "<dark_gray>y " + claim.shape().minY() + " to " + claim.shape().maxY()),
                "The owner's to change",
                click -> new ClaimHeightMenu(services(), viewer, claim, this).open());

        if (services().features().isOffered(ClaimFeature.FENCE)) {
            band(MenuLayout.LAND, 6, may(ClaimAdminPermission.MANAGE_SHAPE),
                    Icons.of(claim.fence().material(), "<green>Fence",
                            "<gray>A real fence along the border.",
                            "<dark_gray>" + (claim.fence().enabled() ? "standing" : "not built")),
                    "The owner's to change",
                    click -> new FenceMenu(services(), viewer, claim, this).open());
        }

        band(MenuLayout.WHO, 4, may(ClaimAdminPermission.MANAGE_TITLES),
                Icons.of(claim.iconMaterial(claim.isOwner(viewer.getUniqueId())),
                        "<green>Name and icon",
                        "<gray>What this claim is called, and what it looks like in a list."),
                "The owner's to change",
                click -> new ClaimIdentityMenu(services(), viewer, claim, this).open());
    }
}
