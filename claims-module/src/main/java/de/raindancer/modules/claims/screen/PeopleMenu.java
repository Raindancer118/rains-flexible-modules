package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Everybody who has anything to do with this claim.
 *
 * <p>Three questions about people, and they were three buttons in three different places on the front page.
 * Together they read as one subject: who you named, what a stranger may do, and who may not come back. The
 * front page carries the player's own head for it, so the category is recognisable before it is read.
 */
public final class PeopleMenu extends ClaimScreen {

    public PeopleMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("People");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        band(MenuLayout.WHO, 2, may(ClaimAdminPermission.MANAGE_MEMBERS),
                Icons.head(viewer.getUniqueId(), "<aqua>Trusted people",
                        "<gray>Who may do what here.",
                        "<dark_gray>" + claim.members().size() + " trusted"),
                "The owner's to change",
                click -> new MembersMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 4, may(ClaimAdminPermission.MANAGE_PERMISSIONS),
                Icons.of(Material.OAK_DOOR, "<aqua>Everybody else",
                        "<gray>What a visitor may do without being trusted.",
                        "<dark_gray>" + claim.publicPermissions().size() + " allowed"),
                "The owner's to change",
                click -> new PublicPermissionsMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 6, may(ClaimAdminPermission.MANAGE_BANS),
                Icons.of(Material.IRON_BARS, "<aqua>Kept out",
                        "<gray>Bans and timeouts.",
                        "<dark_gray>" + claim.bans().size() + " on the list"),
                "The owner's to change",
                click -> new BansMenu(services(), viewer, claim, this).open());
    }
}
