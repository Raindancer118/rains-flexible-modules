package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimFeature;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;

/** What this claim is called, what it looks like in a list, and what it says to somebody arriving. */
public final class ClaimIdentityMenu extends ClaimScreen {

    private static final Duration RENAME_TIMEOUT = Duration.ofSeconds(24);

    public ClaimIdentityMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Name and icon");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean mayRename = may(ClaimAdminPermission.MANAGE_TITLES)
                && services().features().isOffered(ClaimFeature.CLAIM_RENAME);
        boolean mayIcon = may(ClaimAdminPermission.MANAGE_TITLES)
                && services().features().isOffered(ClaimFeature.CLAIM_ICON);

        band(MenuLayout.WHO, 2, mayRename,
                Icons.of(Material.NAME_TAG, "<green>Called <white>" + claim.name(),
                        "<gray>Type a new name in chat.",
                        "<dark_gray>names are unique per owner, not per server"),
                services().features().isOffered(ClaimFeature.CLAIM_RENAME)
                        ? "The owner's to change" : "The server has switched renaming off",
                click -> askForName());

        band(MenuLayout.WHO, 4, mayIcon,
                Icons.of(claim.iconMaterial(true), "<green>Shown as",
                        "<gray>What this claim looks like in a list.",
                        "<dark_gray>click, then click an item in your inventory"),
                services().features().isOffered(ClaimFeature.CLAIM_ICON)
                        ? "The owner's to change" : "The server has switched icons off",
                click -> {
                    // The item in hand, which is the one interaction nobody has to be taught.
                    var held = viewer.getInventory().getItemInMainHand();
                    if (held.getType().isAir()) {
                        tell("claim.hold-an-item");
                        return;
                    }
                    claim.icon(held);
                    services().claimService().saveAsync(claim);
                    tell("claim.icon-changed", "claim", claim.name());
                    refresh();
                });

        band(MenuLayout.WHO, 6, may(ClaimAdminPermission.MANAGE_TITLES)
                        && services().features().isOffered(ClaimFeature.TITLES),
                Icons.of(Material.PAINTING, "<green>What arrivals see",
                        "<gray>The words across the screen on entering and leaving.",
                        "<dark_gray>" + (claim.titles().hasEnterTitle() ? "set" : "not set")),
                "The owner's to change",
                click -> services().screens().titles(viewer, claim));
    }

    /**
     * Asks for a new name in chat.
     *
     * <p>Chat rather than a sign or an anvil: a claim name may be longer than a sign line, and an anvil rename
     * costs a level on some servers. The refusal cases — taken, invalid — come back from the same validation the
     * command uses, so a name refused here is refused there for the same reason.
     */
    private void askForName() {
        viewer.closeInventory();
        tell("claim.ask-new-name", "claim", claim().name());
        boolean asked = services().prompts().ask(viewer.getUniqueId(), "Claims", RENAME_TIMEOUT,
                typed -> {
                    if (!services().claimService().isValidName(typed)) {
                        tell("error.name-invalid");
                        return;
                    }
                    if (!services().names().available(typed, claim().primaryOwner())) {
                        tell("error.name-taken");
                        return;
                    }
                    String was = claim().name();
                    services().claims().rename(claim(), typed);
                    services().claimService().saveAsync(claim());
                    tell("claim.renamed", "old", was, "claim", typed);
                },
                () -> tell("claim.rename-aborted"));
        if (!asked) {
            tell("selection.already-being-asked");
        }
    }
}
