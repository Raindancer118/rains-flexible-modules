package de.raindancer.modules.farmworld.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.world.time.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.farmworld.FarmWorldServices;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import de.raindancer.modules.farmworld.model.Scatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the server does about farm worlds: what an admin can change without leaving the game.
 *
 * <h2>Why this exists beside {@code /settings}</h2>
 * Because {@code /settings} is the whole server's tree and this is the one page about farm worlds. An admin
 * who has just arrived in a hole in the ground and wants to know why should not have to find
 * {@code farmworlds/arriving} in a list of every plugin's topics. It is the same settings underneath — the
 * same {@code SettingsStore}, the same file, the same validation — so the two cannot disagree.
 *
 * <h2>Every click writes to disk, at once</h2>
 * {@code SettingsStore.set} and {@code cycle} both save, so there is no Apply button to forget to press —
 * and no state where the screen and the file say different things.
 *
 * <h2>Layout</h2>
 * Three bands: {@code WHO} is what travels with somebody, {@code RULES} is what a trip costs, {@code LAND} is
 * where they come out and how much notice they get. Four buttons a band, at columns 1 · 3 · 5 · 7, so a pane
 * falls between each pair — a wall of adjacent buttons is unreadable, and there is no eighth column to put a
 * fifth in: {@code MenuLayout} clamps to seven, so a button asked for at 8 would land silently on top of the
 * one at 7.
 *
 * <h2>What is deliberately not on this page</h2>
 * Which farm worlds there are and how often each is regenerated. That is per farm world and it lives on
 * {@code FarmWorldManageMenu}, because a server has one farm world regenerated weekly and another kept for
 * ever — a single button here could not express it, and a button that looked as though it could would be
 * worse than none.
 */
public final class FarmWorldConfigMenu extends Menu implements IFarmWorldScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final FarmWorldServices services;

    public FarmWorldConfigMenu(FarmWorldServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>How farm worlds work here");
    }

    @Override
    public String breadcrumb() {
        return "How farm worlds work here";
    }

    @Override
    protected void render() {
        FarmWorldSettings now = services.config();

        // ------------------------------------------------------------ what comes with you
        toggle(MenuLayout.WHO, 1, Material.LEAD, "Bring what you are leading",
                now.bringWhatYouLead(), "bring-what-you-lead",
                "<gray>A dog on your lead, the boat you are towing,",
                "<gray>and whoever is riding with you.",
                "<dark_gray>Never another player, whatever this says — a",
                "<dark_gray>player moved somewhere they did not ask to go is",
                "<dark_gray>a teleport nobody agreed to.");

        // Greyed rather than hidden when leads are off: which of them is set is exactly what somebody opened
        // this page to find out, and a button that vanishes makes the page a different shape per server.
        band(MenuLayout.WHO, 3, now.bringWhatYouLead(),
                Icons.of(now.bringNearbyPets() ? Material.BONE : Material.BONE_MEAL,
                        "<white>Bring your animals standing nearby",
                        lore(now.bringNearbyPets(),
                                "<gray>Your own tame animals come too, even the ones",
                                "<gray>not on a lead. Costs a look around the player",
                                "<gray>on every trip.",
                                "<dark_gray>Never somebody else's animals, and never a",
                                "<dark_gray>wild mob — a trip taken at a mob farm would",
                                "<dark_gray>otherwise arrive with the mob farm.")),
                "Nothing travels with anybody while leads are switched off",
                click -> cycle("bring-nearby-pets"));

        band(MenuLayout.WHO, 5, now.bringNearbyPets(),
                Icons.of(Material.SPYGLASS, "<white>How far your animals may be",
                        lore(now.bringRadius() + " blocks",
                                "<gray>Only the animals not on a lead are limited by",
                                "<gray>this. Something on a lead comes however far it",
                                "<gray>has drifted.",
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

        // ------------------------------------------------------------ what a trip costs
        band(MenuLayout.RULES, 1, Icons.of(Material.CLOCK, "<white>Stand still for",
                        lore(now.warmup() + " seconds",
                                "<gray>Before the farm world takes somebody, so that",
                                "<gray>escaping a fight into it costs something.",
                                "<dark_gray>Zero sends them at once.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("warmup-seconds", now.warmupSeconds(), click, 0, 60));

        amount(MenuLayout.RULES, 3, "cooldown-seconds", "Seconds between trips",
                now.cooldownSeconds(), 0, 86_400,
                Icons.of(Material.REPEATER, "<white>Wait between trips",
                        lore(Times.describe(now.cooldownFor()),
                                "<gray>Without it, arriving somewhere unpromising and",
                                "<gray>going straight back for another roll of the dice",
                                "<gray>is free — and then where you landed does not",
                                "<gray>matter at all.",
                                "<dark_gray>The wait starts when somebody arrives, so an",
                                "<dark_gray>interrupted trip is free.",
                                "",
                                "<gray>Click to pick the number.")));

        toggle(MenuLayout.RULES, 5, Material.SHIELD, "Being hurt cancels the wait",
                now.hurtCancelsWarmup(), "hurt-cancels-warmup",
                "<gray>Whether taking damage gives up on a trip",
                "<gray>somebody is standing still for.",
                "<dark_gray>Worth thinking about with the wait above: mobs",
                "<dark_gray>at spawn and five seconds is a trip nobody can",
                "<dark_gray>finish, which gets reported as 'it is broken'.");

        band(MenuLayout.RULES, 7, Icons.of(Material.FEATHER, "<white>How far to look for solid ground",
                        lore(now.arrivalRadius() + " blocks",
                                "<gray>Somewhere safe is always looked for. There is",
                                "<gray>no switch for that: a random point in generated",
                                "<gray>terrain is inside stone about as often as it is",
                                "<gray>on grass.",
                                "<dark_gray>This is only how far sideways to give up — and",
                                "<dark_gray>it is also how much world has to be generated",
                                "<dark_gray>to find out, so a large number is a pause for",
                                "<dark_gray>everybody rather than for the traveller.",
                                "",
                                "<gray>Click to add one. Right click to take one away.")),
                click -> step("safe-arrival-radius", now.safeArrivalRadius(), click, 1, 32));

        // ------------------------------------------------------------ where they come out
        toggle(MenuLayout.LAND, 1, Material.FILLED_MAP, "Scatter arrivals",
                now.scatterArrivals(), "scatter-arrivals",
                "<gray>Everybody lands somewhere different. This is",
                "<gray>what makes a farm world one.",
                "<dark_gray>Off, everybody arrives at the world's own spawn,",
                "<dark_gray>the ground around it is bare within a day, and",
                "<dark_gray>every arrival after that is a five-minute walk",
                "<dark_gray>before they can start.");

        amount(MenuLayout.LAND, 3, "scatter-nearest", "Blocks from the middle",
                now.scatterNearest(), 0, Scatter.FURTHEST_ALLOWED, now.scatterArrivals(),
                "Only used when arrivals are scattered",
                Icons.of(Material.LODESTONE, "<white>Nearest anybody lands to the middle",
                        lore(now.scatterNearest() + " blocks",
                                "<gray>Not zero: the middle is where the portals, the",
                                "<gray>roads and whatever an admin built are, and that",
                                "<gray>is the one part worth keeping intact.",
                                "",
                                "<gray>Click to pick the number.")));

        amount(MenuLayout.LAND, 5, "scatter-furthest", "Blocks from the middle",
                now.scatterFurthest(), Scatter.NEAREST_ALLOWED, Scatter.FURTHEST_ALLOWED,
                now.scatterArrivals(), "Only used when arrivals are scattered",
                Icons.of(Material.MAP, "<white>Furthest anybody lands from the middle",
                        lore(now.scatterFurthest() + " blocks",
                                "<gray>Also how much of the world gets generated over",
                                "<gray>its life, one arrival at a time — which is disk",
                                "<gray>nobody notices until it runs out.",
                                "<dark_gray>Kept inside each farm world's own border, so a",
                                "<dark_gray>border smaller than this wins.",
                                "",
                                "<gray>Click to pick the number.")));

        amount(MenuLayout.LAND, 7, "warn-minutes", "Minutes of notice",
                now.warnMinutes(), 0, 1440,
                Icons.of(Material.BELL, "<white>Warn this long before", lore(warningLore(now))));
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
     * A number picked in Core's own chooser rather than nudged.
     *
     * <p>For the four settings whose range is wide. {@code AmountChooser}'s own javadoc names the mistake this
     * avoids, and it is one this page had: nudge buttons are forty clicks to set a value of four hundred, and
     * this page has a wait measured in seconds up to a day and two radii measured in blocks up to a hundred
     * thousand. Stepping those by any size at all is either unusable or unable to express half the values.
     *
     * <p>Nothing is written until Accept, which is why the chooser takes a callback rather than a key: backing
     * out of it changes nothing, and there is no half-set state for the page to draw.
     */
    private void amount(int band, int column, String key, String label, int value, int least, int most,
                        org.bukkit.inventory.ItemStack icon) {
        band(band, column, icon, click -> pick(key, label, value, least, most));
    }

    /** The same, greyed with a reason when another setting has switched it off. */
    private void amount(int band, int column, String key, String label, int value, int least, int most,
                        boolean allowed, String reason, org.bukkit.inventory.ItemStack icon) {
        band(band, column, allowed, icon, reason, click -> pick(key, label, value, least, most));
    }

    private void pick(String key, String label, int value, int least, int most) {
        new AmountChooser(viewer, services.brand(), this, label, value, least, most,
                chosen -> write(key, String.valueOf(chosen))).open();
    }

    /**
     * A number, changed by a click and a right click.
     *
     * <p>Kept for the narrow ranges — a warm-up of nought to sixty, a radius of one to thirty-two. A chooser
     * whose smallest step is ±1 and whose largest is ±100 is the wrong shape for a range of sixty, and a page
     * where every number opens a second window is a page where nothing can be adjusted at a glance.
     *
     * <p>Both halves on one button rather than a {@code +} and a {@code −} beside each other: twelve settings
     * each needing two squares does not fit on a page, and a right click that is advertised in the lore is a
     * right click people find.
     */
    private void step(String key, int from, InventoryClickEvent click, int least, int most) {
        step(key, from, click, least, most, 1);
    }

    private void step(String key, int from, InventoryClickEvent click, int least, int most, int by) {
        int wanted = click.isRightClick() ? from - by : from + by;
        int clamped = Math.max(least, Math.min(most, wanted));
        if (clamped == from) {
            // Already at the end of the range. Saying so beats a click that does nothing, which is a click
            // people make four more times.
            services.messages().send(viewer, "farmworlds.config.at-the-limit",
                    "least", least, "most", most);
            return;
        }
        write(key, String.valueOf(clamped));
    }

    /**
     * Writes one setting and redraws.
     *
     * <p>Through the store, so the file, its validation and the {@code /settings} screens all see the same
     * change — and so it is on disk before the click is finished.
     */
    private void write(String key, String value) {
        if (!services.store().set(key, value)) {
            // Refused by the schema, which is the store doing its job. Said out loud rather than swallowed: a
            // button that silently does nothing is indistinguishable from a broken one.
            services.messages().send(viewer, "farmworlds.config.refused", "setting", key);
            return;
        }
        refresh();
    }

    private void cycle(String key) {
        services.store().cycle(key);
        refresh();
    }

    /**
     * The warning button's lore, which has to name every notice that will actually go out.
     *
     * <p>Read off the rule rather than written here. An admin who sets this to zero and is told nothing else
     * will be said would be told something false — the five- and one-minute notices are given regardless —
     * and a settings page that lies about what the server does is worse than one that says less.
     */
    private List<String> warningLore(FarmWorldSettings now) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Announced to the whole server before a farm");
        lore.add("<gray>world is regenerated.");
        lore.add("");
        lore.add("<dark_gray>Notices that will go out:");
        for (Duration lead : services.notices().warnings().leads()) {
            lore.add("<dark_gray>  " + Times.describe(lead) + " before");
        }
        lore.add("<dark_gray>Five minutes and one minute always go out,");
        lore.add("<dark_gray>whatever this says: those two are 'start walking");
        lore.add("<dark_gray>back' and 'put it somewhere it survives'.");
        lore.add("");
        lore.add("<gray>Click to add five minutes. Right click to take five away.");
        return lore;
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

    /** The same, when the lines are already a list — for a button whose lore is generated. */
    private static List<String> lore(List<String> lines) {
        return List.copyOf(lines);
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("farmworlds.manual.config").stream()
                .map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "what this server does about farm worlds";
    }
}
