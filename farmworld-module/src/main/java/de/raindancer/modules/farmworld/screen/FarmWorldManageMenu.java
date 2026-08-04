package de.raindancer.modules.farmworld.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.world.time.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.farmworld.FarmWorldServices;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One farm world's own settings: how often it goes, how far it reaches, and what it is made of.
 *
 * <h2>Every click writes to disk at once</h2>
 * A schedule that is live now and gone after a restart deletes three worlds on a day nobody expected. There
 * is no Apply button to forget to press, and no state where the screen and the file say different things.
 *
 * <h2>Why the schedule is a list rather than a number</h2>
 * Because the useful answers are a handful of them — off, a day, three days, a week, a fortnight, a month —
 * and the difference between six days and seven is not a difference anybody has an opinion about. A number
 * button stepping by an hour would need a hundred and sixty-eight clicks to reach a week, and one stepping by
 * a day cannot express "off". So it cycles the answers people actually want, and the command takes a
 * duration for the one owner who genuinely wants eleven days.
 *
 * <h2>What is behind the danger slot, and why it is the only thing there</h2>
 * Making the farm world again: up to three worlds deleted and generated afresh, with everything anybody built
 * in them gone. It is the one irreversible action on this page, it is flanked by navigation, and it goes
 * through {@code ConfirmScreen} — so a misclick costs a page rather than everybody's mine.
 */
public final class FarmWorldManageMenu extends Menu implements IFarmWorldScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * The schedules worth offering, in the order the button cycles them. Null is "only when asked".
     *
     * <p>Nothing shorter than a day on purpose. A farm world thrown away every few hours is one nobody can
     * finish a trip in, and the warning that goes out five minutes beforehand would be most of its life.
     */
    private static final List<Duration> SCHEDULES = List.of(
            Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7),
            Duration.ofDays(14), Duration.ofDays(30));

    private final FarmWorldServices services;
    private final String name;

    public FarmWorldManageMenu(FarmWorldServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        // Short enough not to be clipped: the title already carries the parent page's name in front of it.
        return name == null ? "A farm world" : "Change " + name;
    }

    @Override
    protected void render() {
        FarmWorldView farm = services.catalogue().byName(name).orElse(null);
        if (farm == null) {
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<red>It is not there any more",
                    "<gray>Somebody took this farm world off the list",
                    "<gray>while you were looking at it."));
            return;
        }

        // ------------------------------------------------------------ how long it lives
        band(MenuLayout.WHO, 1, Icons.of(Material.CLOCK, "<white>Thrown away every",
                        scheduleLore(farm)),
                click -> cycleSchedule(farm, click));

        band(MenuLayout.WHO, 3, Icons.of(Material.BARRIER, "<white>How far it reaches",
                        borderLore(farm)),
                click -> pickBorder(farm));

        // ------------------------------------------------------------ what it is made of
        band(MenuLayout.WHO, 5, Icons.of(farm.hasNether() ? Material.NETHERRACK : Material.GRAY_DYE,
                        "<white>Its own nether",
                        dimensionLore(farm.hasNether(), "nether",
                                "<gray>A farm nether, so people can mine quartz and",
                                "<gray>farm blaze rods without wrecking the main one.",
                                "<dark_gray>This is what the portal linking is for: without",
                                "<dark_gray>it, a portal lit here leads to the main nether")),
                click -> services.admin().setDimensions(viewer, farm.name(),
                        !farm.hasNether(), farm.hasEnd()));

        band(MenuLayout.WHO, 7, Icons.of(farm.hasEnd() ? Material.END_STONE : Material.GRAY_DYE,
                        "<white>Its own end",
                        dimensionLore(farm.hasEnd(), "end",
                                "<gray>A farm end, for chorus fruit and end stone.",
                                "<dark_gray>Rarely wanted: the main end already regenerates",
                                "<dark_gray>its outer islands, and a farm end has no",
                                "<dark_gray>dragon, so no gateways to the outer ones")),
                click -> services.admin().setDimensions(viewer, farm.name(),
                        farm.hasNether(), !farm.hasEnd()));

        // ------------------------------------------------------------ what is there now
        band(MenuLayout.RULES, 4, Icons.of(Material.SPYGLASS, "<white>What it is right now",
                nowLore(farm)));

        // ------------------------------------------------------------ taking it off the list
        band(MenuLayout.LAND, 4, Icons.of(Material.WRITABLE_BOOK, "<white>Forget this farm world",
                        "<gray>Takes it off the list and stops it being",
                        "<gray>thrown away on a schedule.",
                        "",
                        "<yellow>Its worlds are left exactly where they are.",
                        "<dark_gray>Nothing is deleted — the folders stay, and",
                        "<dark_gray>people already standing there stay too. This is",
                        "<dark_gray>how a farm world becomes an ordinary world you",
                        "<dark_gray>have decided to keep."),
                click -> forget(farm));

        // ------------------------------------------------------------ the one that cannot be undone
        danger(Icons.of(Material.TNT, "<red>Make it again now",
                        "<gray>Deletes its <white>" + farm.worlds().size()
                                + "</white> world(s) and generates them afresh.",
                        "<gray>Everybody standing in them is moved to spawn.",
                        "",
                        "<red>Everything anybody built there is gone.",
                        "<dark_gray>There is no backup and no undo. This is the",
                        "<dark_gray>button the confirmation exists for."),
                click -> confirmRegenerate(farm));
    }

    // ------------------------------------------------------------------------ the buttons

    /**
     * Steps the schedule through the answers worth having, in both directions.
     *
     * <p>Both, because a page that only goes forwards makes correcting a click that went one too far a
     * journey through five more — and the fifth of those is a month, which on the way past is a farm world
     * that will not be thrown away for four weeks.
     */
    private void cycleSchedule(FarmWorldView farm, InventoryClickEvent click) {
        List<Duration> offered = new ArrayList<>();
        // Null first: "only when asked" is one of the answers, and it is the one somebody reaches for when
        // they have decided to keep the world for a while.
        offered.add(null);
        offered.addAll(SCHEDULES);

        Duration now = farm.every().orElse(null);
        int at = offered.indexOf(now);
        if (at < 0) {
            // A period set by command that is not one of the offered ones — eleven days, say. Kept rather
            // than snapped: it is what the owner asked for, and the click moves off it deliberately.
            at = 0;
        }
        int next = click.isRightClick()
                ? (at - 1 + offered.size()) % offered.size()
                : (at + 1) % offered.size();
        services.admin().setSchedule(viewer, farm.name(), offered.get(next));
        refresh();
    }

    /**
     * The border, picked in Core's own chooser.
     *
     * <p>Not a nudge button. {@code AmountChooser}'s own javadoc names why: nudging by any single step across a
     * range of sixty thousand blocks is either a hundred clicks or unable to express half the values, and both
     * versions of that had been written by hand in this repository before it existed.
     *
     * <p>Zero is a real answer rather than the bottom of the range: it means "no border at all", which is what
     * a farm world starts with and what somebody widening one past any useful size actually wants.
     */
    private void pickBorder(FarmWorldView farm) {
        new AmountChooser(viewer, services.brand(), this, "Blocks from the middle, or 0 for none",
                farm.border().orElse(0), 0, FURTHEST_BORDER,
                chosen -> {
                    services.admin().setBorder(viewer, farm.name(), chosen <= 0 ? null : chosen);
                    services.screens().manage(viewer, farm.name());
                }).open();
    }

    /** As far as a border may be set from here. Beyond it, terrain generation is the real limit. */
    private static final int FURTHEST_BORDER = 60_000;

    private void forget(FarmWorldView farm) {
        new ConfirmScreen(services, viewer, this,
                "<yellow>Forget " + farm.name() + "?",
                List.of("<gray>It comes off the list and stops being",
                        "<gray>thrown away on a schedule.",
                        "<green>Its worlds are not deleted."),
                () -> {
                    services.admin().forget(viewer, farm.name());
                    services.notices().forget(farm.name());
                    // Back to the list rather than to this page, which is now about a farm world that is
                    // not there.
                    services.screens().farms(viewer);
                }).open();
    }

    private void confirmRegenerate(FarmWorldView farm) {
        List<String> consequences = new ArrayList<>();
        consequences.add("<gray>Its <white>" + farm.worlds().size()
                + "</white> world(s) are deleted and generated afresh.");
        consequences.add("<gray>Everybody standing in them is moved to spawn.");
        consequences.add("<red>Everything anybody built there is gone.");
        farm.untilRegenerated().ifPresent(left -> consequences.add(
                "<yellow>It was not due for another " + Times.describe(left) + "."));
        new ConfirmScreen(services, viewer, this,
                "<red>Make " + farm.name() + " again?",
                consequences,
                () -> {
                    services.admin().regenerate(viewer, farm.name());
                    services.screens().manage(viewer, farm.name());
                }).open();
    }

    // ------------------------------------------------------------------------ the words on them

    private List<String> scheduleLore(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        lore.add("<yellow>" + farm.every().map(Times::describe).orElse("Only when asked"));
        lore.add("");
        lore.add("<gray>How often it is thrown away and made again.");
        lore.add("<dark_gray>The whole server is warned beforehand — how long");
        lore.add("<dark_gray>before is on the settings page.");
        lore.add("");
        lore.add("<gray>Click for the next one. Right click for the one before.");
        return lore;
    }

    private List<String> borderLore(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        lore.add("<yellow>" + farm.border()
                .map(radius -> radius + " blocks from the middle").orElse("No border"));
        lore.add("");
        lore.add("<gray>How far out the world goes. Also how much of it");
        lore.add("<gray>the server ends up generating, which is disk");
        lore.add("<gray>nobody notices until it runs out.");
        lore.add("<dark_gray>Arrivals are kept inside this automatically, so a");
        lore.add("<dark_gray>border smaller than the scatter radius wins.");
        lore.add("<dark_gray>A change applies when the world is next made.");
        lore.add("");
        lore.add("<gray>Click to pick the number.");
        return lore;
    }

    private static List<String> dimensionLore(boolean on, String which, String... why) {
        List<String> lore = new ArrayList<>(List.of(why));
        lore.add("");
        lore.add(on ? "<green>It has one" : "<red>It has none");
        lore.add("<gray>Click to " + (on ? "stop managing it." : "make it."));
        if (on) {
            lore.add("<dark_gray>Switching it off does not delete the " + which + " —");
            lore.add("<dark_gray>the folder stays and stops being thrown away.");
        } else {
            lore.add("<dark_gray>Making it pauses the server for a moment.");
        }
        return lore;
    }

    private List<String> nowLore(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        for (String world : farm.worlds()) {
            // Asked per world. A farm world whose overworld is up and whose nether never came back is
            // exactly the state somebody opens this page to see, and one answer for all three hides it.
            boolean there = services.catalogue().isLoaded(world);
            lore.add("<dark_gray>" + world + (there ? " <gray>(loaded)" : " <red>(not loaded)"));
        }
        lore.add("");
        farm.untilRegenerated().ifPresentOrElse(
                left -> lore.add("<yellow>" + Times.describe(left) + " until it goes"),
                () -> lore.add(farm.isScheduled()
                        ? "<red>Due to be made again"
                        : "<gray>Not on a schedule"));
        lore.add("");
        lore.add("<dark_gray>Nothing to click. The numbers above are what");
        lore.add("<dark_gray>the file says; this is what the server has.");
        return lore;
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("farmworlds.manual.managing").stream()
                .map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "one farm world's schedule, border and dimensions";
    }
}
