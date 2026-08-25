package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * What this server does about walls and roads: everything an admin can change without leaving the
 * game.
 *
 * <h2>Why this exists beside {@code /settings}</h2>
 * The same reason {@code WarpConfigMenu} does. {@code /settings} is the whole server's tree; this is
 * the one page about roads. Somebody who has just watched a road climb a hill instead of tunnelling
 * through it should not have to find {@code wallsroads/route} in a list of every plugin's topics. It
 * is the same {@code SettingsStore} underneath — same file, same validation — so the two cannot
 * disagree.
 *
 * <h2>Every click writes to disk, at once</h2>
 * No Apply button to forget. A routing threshold that is live now and gone after a restart is found
 * only when the next road comes out as a staircase.
 *
 * <h2>Layout</h2>
 * Three bands, each one question: {@code WHO} is how a road crosses ground, {@code RULES} is how it
 * crosses water and gaps, {@code LAND} is what this server allows at all. Columns 1 · 3 · 5 · 7, so a
 * pane falls between each pair.
 */
public final class WallsRoadsConfigMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WallsRoadsServices services;

    public WallsRoadsConfigMenu(WallsRoadsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>How roads are built here");
    }

    @Override
    public String breadcrumb() {
        return "How roads are built here";
    }

    @Override
    protected void render() {
        WallsRoadsSettings now = services.config();

        // ------------------------------------------------------------- crossing the ground
        band(MenuLayout.WHO, 1, Icons.of(Material.LADDER, "<white>Steepest climb",
                        lore(now.maxGrade() + " block(s) per block travelled",
                                "<gray>How fast a road may rise or fall.",
                                "<dark_gray>1 is a road. Higher is a staircase, and it is",
                                "<dark_gray>also what stops hills being tunnelled: a road",
                                "<dark_gray>allowed to climb anything never goes under it.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("max-grade", now.maxGrade(), click, 1, 4));

        band(MenuLayout.WHO, 3, Icons.of(Material.SMOOTH_STONE, "<white>Terrain smoothing",
                        lore(now.terrainSmoothing() + " columns either side",
                                "<gray>The ground height is averaged over this, so a",
                                "<gray>single boulder does not put a step in the road.",
                                "<dark_gray>0 follows every bump exactly.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("terrain-smoothing", now.terrainSmoothing(), click, 0, 10));

        band(MenuLayout.WHO, 5, Icons.of(Material.OAK_FENCE, "<white>Bridge when this far up",
                        lore(now.bridgeMinGap() + " blocks above the ground",
                                "<gray>Past this the road is built as a bridge, with a",
                                "<gray>railing and piers down to whatever is below.",
                                "<dark_gray>Under it, it is simply a road on a slope.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("bridge-min-gap", now.bridgeMinGap(), click, 1, 16));

        band(MenuLayout.WHO, 7, Icons.of(Material.STONE_BRICKS, "<white>Tunnel when this deep",
                        lore(now.tunnelMinCover() + " blocks under the surface",
                                "<gray>Past this the road is bored out, lined and lit",
                                "<gray>rather than left as a trench.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("tunnel-min-cover", now.tunnelMinCover(), click, 1, 16));

        // ------------------------------------------------------------- crossing water and gaps
        band(MenuLayout.RULES, 1, Icons.of(Material.SCAFFOLDING, "<white>Longest bridge span",
                        lore(now.maxBridgeSpan() + " blocks",
                                "<gray>The widest gap a road holds level across.",
                                "<dark_gray>Anything wider it goes down into — a bridge",
                                "<dark_gray>whose far end is out of sight is not a bridge,",
                                "<dark_gray>it is a road in the sky.",
                                "",
                                "<gray>Click to add eight. Right click to take eight away.")),
                click -> step("max-bridge-span", now.maxBridgeSpan(), click, 4, 256, 8));

        band(MenuLayout.RULES, 3, Icons.of(Material.GLASS, "<white>Sea tunnel from this long",
                        lore(now.seaTunnelMinLength() + " blocks of water",
                                "<gray>A crossing at least this long goes under the",
                                "<gray>water in a glass tunnel instead of over it.",
                                "<dark_gray>Both this and the depth below have to be met,",
                                "<dark_gray>or a stream would be tunnelled under.",
                                "",
                                "<gray>Click to add four. Right click to take four away.")),
                click -> step("sea-tunnel-min-length", now.seaTunnelMinLength(), click, 4, 512, 4));

        band(MenuLayout.RULES, 5, Icons.of(Material.HEART_OF_THE_SEA, "<white>Sea tunnel from this deep",
                        lore(now.seaTunnelMinDepth() + " blocks deep",
                                "<gray>And how deep that water has to be.",
                                "<dark_gray>A long shallow crossing is still a causeway.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("sea-tunnel-min-depth", now.seaTunnelMinDepth(), click, 2, 64));

        band(MenuLayout.RULES, 7, Icons.of(Material.LEAD, "<white>How much a road curves",
                        lore(now.roadCurviness() + " rounds of corner-cutting",
                                "<gray>Applied when a road is marked out.",
                                "<dark_gray>0 keeps the hard angles between the points",
                                "<dark_gray>that were clicked. Roads people actually build",
                                "<dark_gray>curve, and straight segments meeting at corners",
                                "<dark_gray>never look like one.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("road-curviness", now.roadCurviness(), click, 0, 5));

        // ------------------------------------------------------------- what this server allows
        toggle(MenuLayout.LAND, 1, Material.STICK, "Anybody may mark one out",
                now.openCreation(), "open-marking",
                "<gray>Off gates marking behind the create permission,",
                "<gray>for a server that wants only builders placing them.");

        toggle(MenuLayout.LAND, 3, Material.CHEST, "Building costs the blocks",
                now.chargeMaterials(), "charge-materials",
                "<gray>On takes the blocks out of the builder's own",
                "<gray>inventory and stops where they run out.",
                "<dark_gray>Clearing stays free either way, or a tunnel",
                "<dark_gray>would stop half-bored with the hill still in it.");

        toggle(MenuLayout.LAND, 5, Material.FEATHER, "Roads are quicker to walk",
                now.roadSpeedBonus(), "road-speed-bonus",
                "<gray>A small speed bonus on a road this module built.",
                "<dark_gray>It is what makes a road network worth extending",
                "<dark_gray>rather than only worth looking at.");

        toggle(MenuLayout.LAND, 7, Material.CLOCK, "Gates may shut at night",
                now.nightCurfewAllowed(), "night-curfew-allowed",
                "<gray>Lets a wall's owner have its gates close at dusk.",
                "<dark_gray>Off, every gate stays exactly as it was left,",
                "<dark_gray>on every wall on the server.");

        toolbar(2, Icons.of(now.autoPlaceSigns() ? Material.OAK_SIGN : Material.GRAY_DYE,
                        "<white>Signs are put up automatically",
                        lore(now.autoPlaceSigns(),
                                "<gray>A new road gets a name-board at each end, at",
                                "<gray>every gate it passes, and where it meets another.")),
                click -> cycle("auto-place-signs"));

        toolbar(6, Icons.of(Material.OAK_FENCE_GATE, "<white>Height of a new gate",
                        lore(now.gateHeight() + " blocks",
                                "<gray>How tall the opening is where a road cuts",
                                "<gray>through a wall.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("default-gate-height", now.defaultGateHeight(), click, 1, 16));
    }

    // ------------------------------------------------------------------ the two kinds of button

    /** A switch. Shows what it is now, and says which way the click goes. */
    private void toggle(int band, int column, Material icon, String label, boolean on, String key,
                        String... why) {
        band(band, column, Icons.of(on ? icon : Material.GRAY_DYE, "<white>" + label, lore(on, why)),
                click -> cycle(key));
    }

    /**
     * A number, changed by a click and a right click.
     *
     * <p>Both halves on one button rather than a {@code +} and a {@code −} beside each other: fourteen
     * settings each needing two squares does not fit on a page, and a right click advertised in the
     * lore is a right click people find.
     */
    private void step(String key, int from, InventoryClickEvent click, int least, int most) {
        step(key, from, click, least, most, 1);
    }

    private void step(String key, int from, InventoryClickEvent click, int least, int most, int by) {
        int wanted = click.isRightClick() ? from - by : from + by;
        int clamped = Math.max(least, Math.min(most, wanted));
        if (clamped == from) {
            // Already at the end of the range. Saying so beats a click that does nothing, which is a
            // click people make four more times.
            services.messages().send(viewer, "wallsroads.config.at-the-limit",
                    "least", String.valueOf(least), "most", String.valueOf(most));
            return;
        }
        write(key, String.valueOf(clamped));
    }

    /** Writes one setting and redraws — through the store, so it is on disk before the click ends. */
    private void write(String key, String value) {
        if (!services.store().set(key, value)) {
            services.messages().send(viewer, "wallsroads.config.refused", "setting", key);
            return;
        }
        refresh();
    }

    private void cycle(String key) {
        services.store().cycle(key);
        refresh();
    }

    private static List<String> lore(boolean on, String... why) {
        List<String> lines = new ArrayList<>();
        lines.add(on ? "<green>On" : "<red>Off");
        lines.add("");
        lines.addAll(List.of(why));
        lines.add("");
        lines.add("<yellow>Click <gray>to turn it " + (on ? "off" : "on"));
        return lines;
    }

    private static List<String> lore(String value, String... why) {
        List<String> lines = new ArrayList<>();
        lines.add("<white>" + value);
        lines.add("");
        lines.addAll(List.of(why));
        return lines;
    }
}
