package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.service.SponsorBeaconService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Running the sponsor system: the beacons in the world, and the door to the shop that sells at them.
 *
 * <h2>Where the on/off toggles from the source plugin went</h2>
 * The source kept four switches here — sponsors, tokens, beacons, shop — each its own settings key. None
 * of those keys exist in {@link de.raindancer.modules.hungergames.HungerGamesSettings}: sponsors have no
 * topic there yet, exactly as {@link SponsorTokenService}'s own class note says. This page cannot toggle a
 * setting that does not exist, so it does not pretend to; what is real here — the beacon list, giving a
 * test token, and the shop editor — is everything this page can honestly offer today.
 *
 * <h2>Where the shop preview button went</h2>
 * The source plugin's preview opened {@code SponsorShopMenu} straight from here. {@link ShopMenu} needs an
 * {@code AnnouncementService}, an {@code ItemFactory} and its own {@code PlainStack} seam — none of which
 * this page has a reason to hold otherwise — so wiring a preview button here would mean threading three
 * dependencies through this class purely to hand them straight to another screen. Whoever already holds
 * all of {@link ShopMenu}'s dependencies (the module's bootstrap) is a better place to offer that preview.
 */
public final class SponsorAdminMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SponsorTokenService tokens;
    private final SponsorBeaconService beacons;
    private final SponsorShopStore shopStore;
    private final CustomItems customItems;
    private final ChatPrompts prompts;

    public SponsorAdminMenu(Player viewer, Brand brand, Menu parent, SponsorTokenService tokens,
                            SponsorBeaconService beacons, SponsorShopStore shopStore, CustomItems customItems,
                            ChatPrompts prompts) {
        super(viewer, brand, parent);
        this.tokens = tokens;
        this.beacons = beacons;
        this.shopStore = shopStore;
        this.customItems = customItems;
        this.prompts = prompts;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Sponsors");
    }

    @Override
    public String breadcrumb() {
        return "Sponsors";
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 4, Icons.of(Material.NETHER_STAR, "<gold>Sponsors",
                "<gray>" + beacons.statusLine() + " beacon(s) in the world."));

        band(MenuLayout.RULES, 2, Icons.of(Material.BEACON, "<green>Create a beacon here",
                        "<gray>Places one at your position."),
                click -> {
                    Optional<String> error = beacons.createBeacon(viewer.getLocation(), viewer.getName());
                    tell(error.isEmpty() ? "<green>Beacon created."
                            : "<red>Could not create a beacon: " + error.get());
                    refresh();
                });

        band(MenuLayout.RULES, 3, Icons.of(Material.TNT_MINECART, "<yellow>Remove the nearest beacon",
                        "<gray>Whichever active beacon is closest to you."),
                click -> removeNearest());

        band(MenuLayout.RULES, 5, Icons.of(Material.NETHER_STAR, "<yellow>Give myself a test token",
                        "<gray>One real sponsor token."),
                click -> {
                    tokens.giveManually(viewer.getName(), viewer, 1);
                    tell("<green>1 sponsor token received.");
                });

        band(MenuLayout.RULES, 6, Icons.of(Material.EMERALD, "<light_purple>Shop editor",
                        "<gray>Add, edit or remove what tokens buy."),
                click -> new ShopEditorMenu(viewer, brand(), this, shopStore, customItems, prompts).open());

        List<Location> active = beacons.activeBeacons();
        int slot = 1;
        for (Location beacon : active) {
            if (slot > 7) {
                break;
            }
            String coords = beacon.getBlockX() + " / " + beacon.getBlockY() + " / " + beacon.getBlockZ();
            band(MenuLayout.LAND, slot++, Icons.of(Material.BEACON, "<aqua>" + coords,
                            "<dark_gray>Click to teleport there."),
                    click -> viewer.teleport(beacon.clone().add(0.5, 1, 0.5)));
        }

        if (!active.isEmpty()) {
            danger(Icons.of(Material.TNT, "<red>Remove every beacon",
                            "<gray>Removes all " + active.size() + " active beacon(s).",
                            "<dark_gray>Asks first."),
                    click -> new ConfirmScreen(viewer, brand(), this, "<red>Remove every sponsor beacon?",
                            List.of("<gray>" + active.size() + " active beacon(s) disappear from the world."),
                            () -> {
                                int removed = beacons.removeAllBeacons(viewer.getName());
                                tell("<green>" + removed + " beacon(s) removed.");
                                open();
                            }).open());
        }
    }

    private void removeNearest() {
        List<Location> active = beacons.activeBeacons();
        Optional<Location> nearest = active.stream()
                .filter(loc -> loc.getWorld() != null && loc.getWorld().equals(viewer.getWorld()))
                .min(Comparator.comparingDouble(loc -> loc.distanceSquared(viewer.getLocation())));
        if (nearest.isEmpty()) {
            tell("<red>No beacon is active in this world.");
            return;
        }
        beacons.removeBeacon(nearest.get(), viewer.getName());
        tell("<green>Beacon removed.");
        refresh();
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "the sponsor beacons in the world, and the door to what they sell";
    }
}
