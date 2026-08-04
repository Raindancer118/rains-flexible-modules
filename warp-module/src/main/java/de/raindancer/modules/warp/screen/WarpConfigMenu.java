package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.WarpSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * What the server does about warps: what an admin can change without leaving the game.
 *
 * <h2>Why this exists beside {@code /settings}</h2>
 * Because {@code /settings} is the whole server's tree and this is the one page about warps. An admin
 * who has just made a warp and wants to know why it took three seconds should not have to find
 * {@code warps/travelling} in a list of every plugin's topics. It is the same settings underneath —
 * the same {@code SettingsStore}, the same file, the same validation — so the two cannot disagree.
 *
 * <h2>Every click writes to disk, at once</h2>
 * A protection setting that is live now and gone after a restart is found only when somebody notices
 * the staff warp is public again. {@code SettingsStore.set} and {@code cycle} both save, so there is
 * no Apply button to forget to press — and no state where the screen and the file say different
 * things.
 *
 * <h2>Layout</h2>
 * Three bands, following the module's grammar: {@code WHO} is what travels with somebody,
 * {@code RULES} is what a warp costs to use, {@code LAND} is what warps a server may have. Four
 * buttons a band, at columns 1 · 3 · 5 · 7, so a pane falls between each pair — a wall of adjacent
 * buttons is unreadable, and there is no eighth column to put a fifth in: {@code MenuLayout} clamps
 * to seven, so a button asked for at 8 would land silently on top of the one at 7.
 */
public final class WarpConfigMenu extends Menu implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WarpServices services;

    public WarpConfigMenu(WarpServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>How warps work here");
    }

    @Override
    public String breadcrumb() {
        return "How warps work here";
    }

    @Override
    protected void render() {
        WarpSettings now = services.config();

        // ------------------------------------------------------------ what comes with you
        toggle(MenuLayout.WHO, 1, Material.LEAD, "Bring what you are leading",
                now.bringWhatYouLead(), "bring-what-you-lead",
                "<gray>A dog on your lead, the boat you are towing,",
                "<gray>and whoever is riding with you.",
                "<dark_gray>Never another player, whatever this says —",
                "<dark_gray>a player moved somewhere they did not ask to",
                "<dark_gray>go is a teleport nobody agreed to.");

        // Greyed rather than hidden when leads are off: which of them is set is exactly what somebody
        // opened this page to find out, and a button that vanishes makes the page a different shape
        // per server.
        band(MenuLayout.WHO, 3, now.bringWhatYouLead(),
                Icons.of(now.bringNearbyPets() ? Material.BONE : Material.BONE_MEAL,
                        "<white>Bring your animals standing nearby",
                        lore(now.bringNearbyPets(),
                                "<gray>Your own tame animals come too, even the",
                                "<gray>ones not on a lead. Costs a look around the",
                                "<gray>player on every warp.",
                                "<dark_gray>Never somebody else's animals, and never a",
                                "<dark_gray>wild mob — a warp taken at a mob farm would",
                                "<dark_gray>otherwise arrive with the mob farm.")),
                "Nothing travels with anybody while leads are switched off",
                click -> cycle("bring-nearby-pets"));

        band(MenuLayout.WHO, 5, now.bringNearbyPets(),
                Icons.of(Material.SPYGLASS, "<white>How far your animals may be",
                        lore(now.bringRadius() + " blocks",
                                "<gray>Only the animals not on a lead are limited",
                                "<gray>by this. Something on a lead comes however",
                                "<gray>far it has drifted.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                "Only used when animals standing nearby are brought",
                click -> step("bring-radius", now.bringRadius(), click, 1, 32));

        band(MenuLayout.WHO, 7, now.bringWhatYouLead(),
                Icons.of(Material.HOPPER, "<white>Most that may come at once",
                        lore(String.valueOf(now.bringAtMost()),
                                "<gray>A hundred entities teleported at once is a",
                                "<gray>pause for everybody on the server.",
                                "<dark_gray>What is on a lead is brought first when this",
                                "<dark_gray>bites, because that is what somebody meant.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                "Nothing travels with anybody while leads are switched off",
                click -> step("bring-at-most", now.bringAtMost(), click, 1, 20));

        // ------------------------------------------------------------ what a warp costs
        band(MenuLayout.RULES, 1, Icons.of(Material.CLOCK, "<white>Stand still for",
                        lore(now.warmup() + " seconds",
                                "<gray>Before a warp takes somebody, so that running",
                                "<gray>out of a fight through a warp costs something.",
                                "<dark_gray>Zero sends them at once.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("warmup-seconds", now.warmupSeconds(), click, 0, 60));

        band(MenuLayout.RULES, 3, Icons.of(Material.REPEATER, "<white>Wait between warps",
                        lore(now.cooldown() + " seconds",
                                "<gray>One wait for all warps, not one per warp:",
                                "<gray>a wait per warp means hopping between two of",
                                "<gray>them costs nothing at all.",
                                "<dark_gray>Zero switches it off. The wait starts when",
                                "<dark_gray>somebody arrives, so an interrupted warp is free.",
                                "",
                                "<gray>Click to add five. Right click to take five away.")),
                click -> step("cooldown-seconds", now.cooldownSeconds(), click, 0, 3600, 5));

        toggle(MenuLayout.RULES, 5, Material.SHIELD, "Being hurt cancels the wait",
                now.hurtCancelsWarmup(), "hurt-cancels-warmup",
                "<gray>Whether taking damage gives up on a warp",
                "<gray>somebody is standing still for.",
                "<dark_gray>Worth thinking about with the wait above: mobs",
                "<dark_gray>at spawn and five seconds is a warp nobody can",
                "<dark_gray>finish, which gets reported as 'warps are broken'.");

        toggle(MenuLayout.RULES, 7, Material.FEATHER, "Look for somewhere safe to land",
                now.safeArrival(), "safe-arrival",
                "<gray>Arriving looks for solid ground near the warp",
                "<gray>rather than dropping somebody exactly where it",
                "<gray>was set.",
                "<dark_gray>A warp set on a boat, or one whose ground has",
                "<dark_gray>since been mined out, is otherwise a fall.",
                "<dark_gray>Nowhere safe is a refusal, never a drop anyway.");

        // ------------------------------------------------------------ what warps there may be
        band(MenuLayout.LAND, 1, now.safeArrival(),
                Icons.of(Material.SPYGLASS, "<white>How far to look for it",
                        lore(now.arrivalRadius() + " blocks",
                                "<gray>Also how much of the world has to be brought",
                                "<gray>in to find out, so a large number is a pause",
                                "<gray>for everybody, not only the person warping.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                "Only used when a safe landing is looked for",
                click -> step("safe-arrival-radius", now.safeArrivalRadius(), click, 1, 32));

        toggle(MenuLayout.LAND, 3, Material.BOOKSHELF, "Group warps into categories",
                now.useCategories(), "use-categories",
                "<gray>Whether the warp menu offers the categories page.",
                "<dark_gray>Off, every warp is on one list — right for a",
                "<dark_gray>server with eight and unusable with eighty.");

        band(MenuLayout.LAND, 5, Icons.of(Material.CHEST, "<white>Warps this server may have",
                        lore(String.valueOf(now.warpLimit()),
                                "<gray>A ceiling, so a script cannot fill the store.",
                                "<dark_gray>Reaching it refuses the next one out loud.",
                                "",
                                "<gray>Click to add ten. Right click to take ten away.")),
                click -> step("most-warps", now.mostWarps(), click, 1, 5000, 10));

        band(MenuLayout.LAND, 7, Icons.of(Material.NAME_TAG, "<white>Longest a warp's name may be",
                        lore(now.nameLimit() + " characters",
                                "<gray>A name longer than this cannot be read in the",
                                "<gray>menu it appears in, and the button that opens",
                                "<gray>it is what people look for.",
                                "<dark_gray>Lowering it does not rename the warps that are",
                                "<dark_gray>already longer — it only refuses new ones.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("longest-name", now.longestName(), click, 3, 48));
    }

    // ------------------------------------------------------------------------ the two kinds of button

    /** A switch. Shows what it is now, and says which way the click goes. */
    private void toggle(int band, int column, Material icon, String label, boolean on, String key,
                        String... why) {
        band(band, column, Icons.of(on ? icon : Material.GRAY_DYE, "<white>" + label,
                        lore(on, why)),
                click -> cycle(key));
    }

    /**
     * A number, changed by a click and a right click.
     *
     * <p>Both halves on one button rather than a {@code +} and a {@code −} beside each other: eleven
     * settings each needing two squares does not fit on a page, and a right click that is advertised
     * in the lore is a right click people find.
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
            services.messages().send(viewer, "warps.config.at-the-limit",
                    "least", least, "most", most);
            return;
        }
        write(key, String.valueOf(clamped));
    }

    /**
     * Writes one setting and redraws.
     *
     * <p>Through the store, so the file, its validation and the {@code /settings} screens all see the
     * same change — and so it is on disk before the click is finished.
     */
    private void write(String key, String value) {
        if (!services.store().set(key, value)) {
            // Refused by the schema, which is the store doing its job. Said out loud rather than
            // swallowed: a button that silently does nothing is indistinguishable from a broken one.
            services.messages().send(viewer, "warps.config.refused", "setting", key);
            return;
        }
        refresh();
    }

    private void cycle(String key) {
        services.store().cycle(key);
        refresh();
    }

    private static List<String> lore(boolean on, String... why) {
        List<String> lines = new ArrayList<>(List.of(why));
        lines.add("");
        lines.add(on ? "<green>On" : "<red>Off");
        lines.add("<gray>Click to turn it " + (on ? "off." : "on."));
        return lines;
    }

    private static List<String> lore(String value, String... why) {
        List<String> lines = new ArrayList<>();
        lines.add("<yellow>" + value);
        lines.add("");
        lines.addAll(List.of(why));
        return lines;
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("warps.manual.config").stream()
                .map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "what this server does about warps";
    }
}
