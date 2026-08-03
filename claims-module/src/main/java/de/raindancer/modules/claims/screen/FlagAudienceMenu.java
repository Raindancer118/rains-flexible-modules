package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * One rule, set separately for the three kinds of person.
 *
 * <p>Three buttons and a fourth that clears the lot back to the server's default — which is the button the old
 * screen lacked, so an owner who had once touched a flag could never get back to "whatever the server says" and
 * would be pinned to the value it happened to have that day.
 */
public final class FlagAudienceMenu extends ClaimScreen {

    private final LandFlag flag;

    public FlagAudienceMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, LandFlag flag) {
        super(services, viewer, claim, parent, 3);
        this.flag = flag;
    }

    @Override
    protected Component title() {
        return Component.text(services().messages().raw(flag.nameKey()));
    }

    @Override
    protected void render() {
        int column = 2;
        for (LandAudience audience : LandAudience.values()) {
            boolean on = services().flags().isAllowed(claim().area(), flag, audience);
            band(MenuLayout.WHO, column, Icons.of(audience.icon(),
                            (on ? "<green>" : "<red>") + services().messages().raw(audience.nameKey()),
                            "<gray>" + services().messages().raw(audience.descriptionKey()),
                            "",
                            on ? "<green>✔ allowed" : "<red>✘ not allowed",
                            "<dark_gray>click to change"),
                    click -> {
                        claim().setFlagOverride(flag, audience, !on);
                        services().claimService().saveAsync(claim());
                        refresh();
                    });
            column += 2;
        }

        toolbar(4, Icons.of(Material.STRUCTURE_VOID, "<gray>Follow the server again",
                        "<gray>Forget what you set here.",
                        "<dark_gray>this rule goes back to the server's default"),
                click -> {
                    claim().setFlagOverride(flag, null, null);
                    services().claimService().saveAsync(claim());
                    refresh();
                });
    }
}
