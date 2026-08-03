package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The server owner's page: what claims are doing, and the two things only an admin decides.
 *
 * <p>Small on purpose. Most of what the old admin menu held is now in Core's settings screen, where it belongs
 * alongside every other plugin's settings rather than in a page of its own — an admin looking for a number should
 * find it in one place, not in nine plugin-specific menus.
 *
 * <p>What is left is what is genuinely about claims: which features owners are offered, where nobody may claim,
 * and a browser for other people's claims.
 */
public final class AdminMenu extends ClaimScreen {

    public AdminMenu(ClaimServices services, Player viewer, Menu parent) {
        super(services, viewer, null, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Claims — server");
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 2, Icons.of(Material.COMPARATOR, "<gold>What owners may do",
                        "<gray>Which perks a claim is offered at all.",
                        "<dark_gray>server-wide"),
                click -> new FeaturesMenu(services(), viewer, null, this).open());

        band(MenuLayout.WHO, 3, Icons.of(Material.LEVER, "<gold>Flags",
                        "<gray>Which flags owners may change,",
                        "<gray>and what a new claim starts with.",
                        "<dark_gray>server-wide · saved as you click"),
                click -> new FlagPolicyMenu(services(), viewer, this).open());

        band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<gold>Where nobody may claim",
                        "<gray>" + services().zones().all().size() + " area(s) marked out.",
                        "<dark_gray>mark another with /claimadmin zone"),
                click -> new ZonesMenu(services(), viewer, this).open());

        band(MenuLayout.WHO, 6, Icons.of(Material.SPYGLASS,
                        services().land().isBypassing(viewer)
                                ? "<green>Bypass is on" : "<gray>Bypass is off",
                        "<gray>Ignore every claim's protection.",
                        "<dark_gray>Core's, so it covers every kind of protected ground"),
                click -> {
                    services().land().toggleBypass(viewer);
                    refresh();
                });

        toolbar(4, Icons.of(Material.BOOK, "<white>What is running",
                List.of("<gray>" + services().claims().size() + " claim(s)",
                        "<gray>" + services().zones().all().size() + " no-claim zone(s)",
                        "<gray>" + services().provider().tracked() + " player(s) tracked",
                        "<gray>answering land questions: <white>"
                                + services().land().provider().map(who -> who.name()).orElse("nobody"))),
                click -> {
                    // A tile to read.
                });
    }
}
