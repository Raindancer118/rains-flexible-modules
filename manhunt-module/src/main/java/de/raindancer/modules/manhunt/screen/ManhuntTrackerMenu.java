package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Everything about the tracking compass, in one page: whether the Hunters carry one at all, who it
 * follows, what it does about a Runner in another dimension, whether it says the distance, whether a
 * dead Hunter gets a new one, and how often the needle re-aims.
 *
 * <h2>A page of its own, off the lobby rather than off {@link ManhuntOptionsMenu}</h2>
 * The compass is six settings, which is a category — and a thing inside {@code Options} may not be
 * another category (see the GUI conventions in {@code Project.md}: three levels means nobody can say
 * where anything lives). So it sits beside Options in the lobby's toolbar, two columns clear of the
 * achievements button, and both are one step from the front page.
 *
 * <h2>The same store, not a second copy of it</h2>
 * Every click here goes through the same {@code SettingsStore} the server's generic {@code /settings}
 * command edits — {@link #cycle} for a flag or a choice, {@link #bump} for the one number, which is
 * clamped to the {@code @Range} the record itself declares before being offered, since
 * {@code SettingsStore.set} refuses an out-of-range value rather than clamping it. There is exactly
 * one place any of these six can actually change.
 */
public final class ManhuntTrackerMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Both ends of {@code tracker-refresh-ticks}' own {@code @Range}, kept in step with it. */
    private static final int FASTEST = 1;
    private static final int SLOWEST = 100;
    private static final int STEP = 5;

    private final ManhuntServices services;

    public ManhuntTrackerMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Tracking compass");
    }

    @Override
    public String breadcrumb() {
        return "Compass";
    }

    @Override
    protected void render() {
        ManhuntSettings config = services.config();

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.COMPASS,
                config.trackerCompassEnabled() ? "<gold>The Hunters carry a compass" : "<gray>No compass",
                "<gray>Everything the tracking compass does,",
                "<gray>and everything it refuses to give away."));

        band(MenuLayout.WHO, 1, flagIcon(config.trackerCompassEnabled(), "Tracking compass",
                        "Whether a Hunter is handed one when a hunt starts."),
                click -> cycle("tracker-compass-enabled"));

        band(MenuLayout.WHO, 3, targetsIcon(config), click -> cycle("tracker-targets"));

        band(MenuLayout.WHO, 5, crossWorldIcon(config), click -> cycle("tracker-cross-world"));

        band(MenuLayout.WHO, 7, flagIcon(config.trackerShowDistance(), "Show the distance",
                        "Off: a direction, and never how far away they are."),
                click -> cycle("tracker-show-distance"));

        band(MenuLayout.RULES, 3, flagIcon(config.trackerSharedTarget(), "One pack, one needle",
                        "A Hunter's pick turns every Hunter's compass, not only their own."),
                click -> cycle("tracker-shared-target"));

        band(MenuLayout.RULES, 1, flagIcon(config.trackerGiveOnRespawn(), "Replace it on respawn",
                        "A Hunter who died drops their compass with everything else."),
                click -> cycle("tracker-give-on-respawn"));

        // The two halves of one decision, side by side — the one place these conventions allow
        // adjacent buttons.
        band(MenuLayout.RULES, 5, Icons.of(Material.RED_CANDLE, "<gold>Slower needle",
                        "<gray>Re-aim less often: <white>" + config.trackerRefreshTicksClamped() + "<gray> ticks.",
                        "<dark_gray>Click to add " + STEP + " ticks."),
                click -> bump(+STEP));
        band(MenuLayout.RULES, 6, Icons.of(Material.GREEN_CANDLE, "<gold>Faster needle",
                        "<gray>Re-aim more often: <white>" + config.trackerRefreshTicksClamped() + "<gray> ticks.",
                        "<dark_gray>Click to take " + STEP + " ticks off."),
                click -> bump(-STEP));
    }

    private void cycle(String key) {
        services.store().cycle(key);
        services.store().save();
        refresh();
    }

    private void bump(int by) {
        int wanted = services.config().trackerRefreshTicksClamped() + by;
        int clamped = Math.max(FASTEST, Math.min(SLOWEST, wanted));
        services.store().set("tracker-refresh-ticks", Integer.toString(clamped));
        services.store().save();
        refresh();
    }

    private ItemStack targetsIcon(ManhuntSettings config) {
        boolean chosen = config.trackerTargets() == ManhuntSettings.TrackerTargets.CHOSEN;
        return Icons.of(chosen ? Material.PLAYER_HEAD : Material.SPYGLASS,
                "<gold>Follows: " + config.trackerTargets(),
                chosen
                        ? "<gray>A Hunter right-clicks to pick one Runner and stays on them."
                        : "<gray>The needle always swings to whoever is nearest.",
                "<dark_gray>Click to cycle.");
    }

    private ItemStack crossWorldIcon(ManhuntSettings config) {
        return switch (config.trackerCrossWorld()) {
            case LAST_PORTAL -> Icons.of(Material.OBSIDIAN,
                    "<gold>Another dimension: LAST_PORTAL",
                    "<gray>Points at the portal the Runner went through,",
                    "<gray>so the Hunters can follow them down.",
                    "<dark_gray>Click to cycle.");
            case NAME_WORLD -> Icons.of(Material.FILLED_MAP,
                    "<gold>Another dimension: NAME_WORLD",
                    "<gray>Names the dimension they are in, and no more.",
                    "<dark_gray>Click to cycle.");
            case HIDDEN -> Icons.of(Material.BLACK_WOOL,
                    "<gold>Another dimension: HIDDEN",
                    "<gray>Says nothing at all — the hardest hunt.",
                    "<dark_gray>Click to cycle.");
        };
    }

    private ItemStack flagIcon(boolean on, String name, String description) {
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "<gold>" + name + ": " + (on ? "<green>on" : "<red>off"),
                "<gray>" + description, "<dark_gray>Click to toggle.");
    }

    public String describe() {
        return "every tracking-compass setting: who it follows, what it gives away, and how fast it re-aims";
    }
}
