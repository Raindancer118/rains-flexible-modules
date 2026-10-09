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

    /** The settings topics the money doors open, relative to the module's settings id. */
    public static final List<String> MONEY_TOPICS = List.of(
            "management/cost", "management/entry-fee", "management/upkeep", "management/slots");

    public AdminMenu(ClaimServices services, Player viewer, Menu parent) {
        // A full page, not the three-row dialog this used to be: a fourth door — browsing every claim on the
        // server — needs a band of its own, and the WHO band was already full at four buttons. A dialog has
        // no row for a second band at all, so the toolbar tile below silently never rendered before this;
        // fixed as a side effect of giving the page room to hold what it now needs to hold.
        super(services, viewer, null, parent);
    }

    private void settings(String path) {
        new de.raindancer.core.data.settings.SettingsMenu(viewer, services().brand(),
                services().core().chatFor(services().brand()), services().core().settingsNavigation(), "claims/" + path, this)
                .open();
    }

    @Override
    protected Component title() {
        // Just "Server". The brand is already in front of every window title, so "Claims — server" rendered
        // as "Claims » Claims — server" — the plugin's own name twice in a title that gets 154 pixels.
        return Component.text("Server");
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 1, Icons.of(Material.COMPARATOR, "<gold>What owners may do",
                        "<gray>Which perks a claim is offered at all.",
                        "<dark_gray>server-wide"),
                click -> new FeaturesMenu(services(), viewer, null, this).open());

        band(MenuLayout.WHO, 3, Icons.of(Material.LEVER, "<gold>Flags",
                        "<gray>Which flags owners may change,",
                        "<gray>and what a new claim starts with.",
                        "<dark_gray>server-wide · saved as you click"),
                click -> new FlagPolicyMenu(services(), viewer, this).open());

        band(MenuLayout.WHO, 5, Icons.of(Material.BARRIER, "<gold>Where nobody may claim",
                        "<gray>" + services().zones().all().size() + " area(s) marked out.",
                        "<dark_gray>open it to mark another out"),
                click -> new ZonesMenu(services(), viewer, this).open());

        band(MenuLayout.RULES, 4, Icons.of(Material.WRITTEN_BOOK, "<gold>Browse every claim",
                        "<gray>Find, inspect, teleport to or reassign",
                        "<gray>a claim regardless of who owns it.",
                        "<dark_gray>" + services().claims().size() + " claim(s) on the server"),
                click -> new AdminClaimBrowserMenu(services(), viewer, this).open());

        // The money: Core's generated settings pages, one door each, so an owner who wants to charge for claims
        // does not have to know the page exists. Status in the lore, because a door that does not say whether the
        // thing behind it is on is a door nobody opens.
        var config = services().config();
        band(MenuLayout.LAND, 2, Icons.of(Material.GOLD_INGOT, "<gold>What a claim costs",
                        "<gray>Price to make one, and the refunds.",
                        "<dark_gray>" + (config.creationCostType() == de.raindancer.modules.claims.model.CostType.NONE
                                ? "free" : "paid in " + config.creationCostType().displayName())),
                click -> settings("management/cost"));

        band(MenuLayout.LAND, 4, Icons.of(Material.GOLD_NUGGET, "<gold>Entry fees",
                        "<gray>What owners may charge, and the",
                        "<gray>share the server keeps.",
                        "<dark_gray>server keeps " + (long) config.entryFeeServerCutPercent() + "%"),
                click -> settings("management/entry-fee"));

        band(MenuLayout.LAND, 8, Icons.of(Material.EMERALD, "<gold>Buying claim slots",
                        "<gray>Extra claims players may buy,", "<gray>and what each one costs.",
                        config.claimSlotBuying() ? "<green>on" : "<dark_gray>off"),
                click -> settings("management/slots"));

        band(MenuLayout.LAND, 6, Icons.of(Material.CLOCK, "<gold>Upkeep",
                        "<gray>Per chunk, per claim, what operators pay.",
                        config.upkeepEnabled() ? "<green>on" : "<dark_gray>off"),
                click -> settings("management/upkeep"));

        band(MenuLayout.WHO, 7, Icons.of(Material.SPYGLASS,
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
