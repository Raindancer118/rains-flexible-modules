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
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything about how this claim is set up, as opposed to who is in it.
 *
 * <p>The front page used to carry all of these, which meant the two questions people actually arrive with —
 * "who is allowed in here" and "how is this thing configured" — were mixed together across three rows. These
 * are the second question: the flags, what people see at the border, how far up and down it reaches, the perks
 * it runs, and the fence.
 */
public final class ConfigMenu extends ClaimScreen {

    private final Consumer<Claim> openTheFlags;
    private final int changedFlags;

    public ConfigMenu(ClaimServices services, Player viewer, Claim claim, Menu parent,
                      Consumer<Claim> openTheFlags, int changedFlags) {
        super(services, viewer, claim, parent);
        this.openTheFlags = openTheFlags;
        this.changedFlags = changedFlags;
    }

    @Override
    protected Component title() {
        return Component.text("Configuration");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        band(MenuLayout.WHO, 2, may(ClaimAdminPermission.MANAGE_FLAGS),
                Icons.of(Material.REDSTONE_TORCH, "<gold>Claim flags",
                        "<gray>Fire, mobs, explosions, PvP —",
                        "<gray>what the world does inside your border.",
                        "<dark_gray>" + changedFlags + " changed from the server default"),
                "The owner's to change",
                click -> openTheFlags.accept(claim));

        band(MenuLayout.WHO, 4, may(ClaimAdminPermission.MANAGE_TITLES),
                Icons.of(Material.NAME_TAG, "<gold>Greetings",
                        "<gray>What people see coming in and going out.",
                        "<dark_gray>" + (claim.titles().enterTitle().raw().isBlank()
                                ? "nothing set" : "set")),
                "The owner's to change",
                click -> new TitlesMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 6, may(ClaimAdminPermission.MANAGE_SHAPE),
                Icons.of(Material.LADDER, "<gold>How deep and how high",
                        "<gray>Change the height without redrawing.",
                        "<dark_gray>y " + claim.shape().minY() + " to " + claim.shape().maxY()),
                "The owner's to change",
                click -> new ClaimHeightMenu(services(), viewer, claim, this).open());

        band(MenuLayout.LAND, 2, Icons.of(Material.BREWING_STAND, "<gold>Perks",
                        "<gray>Effects, the pantry, auto-equip, the weather.",
                        "<dark_gray>" + livePerks() + " running"),
                click -> new PerksMenu(services(), viewer, claim, this).open());

        if (services().features().isOffered(ClaimFeature.FENCE)) {
            band(MenuLayout.LAND, 4, may(ClaimAdminPermission.MANAGE_SHAPE),
                    Icons.of(claim.fence().material(), "<gold>Fence",
                            "<gray>A real fence along the border.",
                            "<dark_gray>" + (claim.fence().enabled() ? "standing" : "not built")),
                    "The owner's to change",
                    click -> new FenceMenu(services(), viewer, claim, this).open());
        }
    }

    /** How many perks are actually running here, for the count on the button. */
    private int livePerks() {
        int live = 0;
        for (ClaimFeature feature : ClaimFeature.values()) {
            if (services().features().isEnabled(claim(), feature)) {
                live++;
            }
        }
        return live;
    }
}
