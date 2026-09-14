package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * How a hunt is set up, in three bands: the win conditions and the map on the first, what a death
 * costs on the second, and what happens when it is over on the third — plus the one button that opens
 * {@link ManhuntGoalMenu} rather than cycling in place. The tracking compass has a page of its own
 * ({@link ManhuntTrackerMenu}) because it is six settings about one thing; everything else in
 * {@link ManhuntSettings} stays reachable through the server's generic {@code /settings} command,
 * which already renders every field of it with an icon. Each click here cycles the exact same {@code SettingsStore} that
 * command edits, through {@code SettingsStore.cycle}, so there is exactly one place any of these
 * values can actually change — this menu is a shortcut to it, not a second copy of it.
 *
 * <h2>Why {@link de.raindancer.modules.manhunt.util.PermissionNodes#ADMIN}</h2>
 * Changing how a hunt is configured is the same class of decision as starting or stopping one —
 * {@code ManhuntLobbyMenu} already gates both behind the same node, and this menu's own button in
 * that lobby is gated identically before this class is ever reached.
 */
public final class ManhuntOptionsMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntOptionsMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Manhunt Options");
    }

    @Override
    public String breadcrumb() {
        return "Options";
    }

    @Override
    protected void render() {
        ManhuntSettings config = services.config();

        // How it is won, and on what ground. Columns 1 · 3 · 5 · 7, so a pane falls between every
        // pair — the GUI conventions' one hard layout rule, and the reason this page used to be
        // unreadable: it ran 1 · 2 · 3 · 4 · 5 · 6 straight across the band.
        band(MenuLayout.WHO, 1, runnerWinIcon(config), click -> cycle("runner-win"));
        band(MenuLayout.WHO, 3, hunterWinIcon(config), click -> cycle("hunter-win"));
        band(MenuLayout.WHO, 5, goalIcon(config),
                click -> new ManhuntGoalMenu(services, viewer, this).open());
        band(MenuLayout.WHO, 7, mapIcon(config),
                click -> new ManhuntMapMenu(services, viewer, this).open());

        // What a death costs.
        band(MenuLayout.RULES, 1, Icons.of(Material.CLOCK,
                        "<white>Countdown: <green>" + config.countdownSecondsClamped() + "s",
                        "<gray>Everybody frozen this long before a hunt starts.",
                        "<dark_gray>0 starts the moment it is asked for.",
                        "<dark_gray>Click to choose a number."),
                click -> choose("Countdown before the hunt (seconds)", "countdown-seconds",
                        config.countdownSecondsClamped(), 0, 60));
        band(MenuLayout.RULES, 3, deathRuleIcon(config), click -> cycle("runner-death-rule"));
        band(MenuLayout.RULES, 5, Icons.of(Material.TOTEM_OF_UNDYING,
                        "<white>Lives per Runner: <green>" + config.runnerLivesClamped(),
                        "<gray>Only used when the rule beside this is LIVES.",
                        "<dark_gray>Click to choose a number."),
                click -> choose("Lives per Runner", "runner-lives",
                        config.runnerLivesClamped(), 1, 10));
        band(MenuLayout.RULES, 7, flagIcon(config.eliminatedSpectate(), "Out means watching",
                        "An eliminated Runner is put into Spectator rather than left standing."),
                click -> cycle("eliminated-spectate"));

        // Before it starts, and after it ends.
        band(MenuLayout.LAND, 1, flagIcon(config.closeWhitelistOnStart(), "Close the whitelist",
                        "Everybody online is snapshotted as whitelisted when the countdown ends."),
                click -> cycle("close-whitelist-on-start"));
        band(MenuLayout.LAND, 3, Icons.of(Material.SKELETON_SKULL,
                        "<white>A dead Hunter waits: <green>"
                                + config.hunterRespawnDelaySecondsClamped() + "s",
                        "<gray>Held in Spectator this long after dying.",
                        "<dark_gray>Click to choose a number."),
                click -> choose("Seconds a dead Hunter waits", "hunter-respawn-delay-seconds",
                        config.hunterRespawnDelaySecondsClamped(), 0, 300));
        band(MenuLayout.LAND, 5, flagIcon(config.returnToLobbyOnFinish(), "Back to the lobby",
                        "Everybody is returned to the waiting lobby once a hunt ends."),
                click -> cycle("return-to-lobby-on-finish"));
        band(MenuLayout.LAND, 7, flagIcon(config.keepRosterOnFinish(), "Keep the sides",
                        "The two rosters survive a hunt, so the next one starts with the same sides."),
                click -> cycle("keep-roster-on-finish"));
    }

    /** The door to {@link ManhuntMapMenu}, saying what is behind it without needing the click. */
    private ItemStack mapIcon(ManhuntSettings config) {
        return Icons.of(Material.FILLED_MAP, "<gold>The map",
                "<gray>Which world, whether it is remade, and its seed.",
                "<dark_gray>" + (config.resetOnStart() ? "remade each run" : "kept between runs")
                        + ", " + config.seedChoice().name().toLowerCase(java.util.Locale.ROOT) + " seed",
                "<dark_gray>Click to open.");
    }

    /**
     * A number, through Core's own picker.
     *
     * <p>This page used to nudge every number with a ±pair of candles, or a left/right click worth
     * five — twelve clicks to cross a range of sixty, and two adjacent buttons where the conventions
     * allow adjacency only for exactly that pattern. {@code AmountChooser} is what every other module
     * in this reactor already uses for the same job, it writes nothing until Accept, and it is one
     * button instead of two. See {@code EntryFeeMenu}, which wrote the same note first.
     */
    private void choose(String label, String key, int current, int least, int most) {
        new AmountChooser(viewer, services.brand(), this, label, current, least, most, value -> {
            services.store().set(key, Integer.toString(value));
            services.store().save();
            refresh();
        }).open();
    }


    private ItemStack deathRuleIcon(ManhuntSettings config) {
        Material icon = switch (config.runnerDeathRule()) {
            case RESPAWN -> Material.WHITE_BED;
            case ELIMINATE -> Material.SKELETON_SKULL;
            case LIVES -> Material.TOTEM_OF_UNDYING;
        };
        String what = switch (config.runnerDeathRule()) {
            case RESPAWN -> "<gray>A Runner's death costs them nothing but time.";
            case ELIMINATE -> "<gray>One death and a Runner is out of the hunt.";
            case LIVES -> "<gray>A Runner is out after the number of deaths set beside this.";
        };
        return Icons.of(icon, "<gold>A Runner's death: " + config.runnerDeathRule(), what,
                "<dark_gray>Click to cycle.");
    }

    private void cycle(String key) {
        services.store().cycle(key);
        services.store().save();
        refresh();
    }

    private ItemStack runnerWinIcon(ManhuntSettings config) {
        boolean advancement = config.runnerWin() == ManhuntSettings.RunnerWinCondition.ADVANCEMENT;
        return Icons.of(advancement ? Material.NETHER_STAR : Material.ENDER_EYE,
                "<gold>Runner win: " + config.runnerWin(),
                "<gray>How the Runners win.", "<dark_gray>Click to cycle.");
    }

    private ItemStack hunterWinIcon(ManhuntSettings config) {
        boolean timeout = config.hunterWin() == ManhuntSettings.HunterWinCondition.TIMEOUT;
        return Icons.of(timeout ? Material.CLOCK : Material.IRON_SWORD,
                "<gold>Hunter win: " + config.hunterWin(),
                "<gray>How the Hunters win.", "<dark_gray>Click to cycle.");
    }

    private ItemStack goalIcon(ManhuntSettings config) {
        String key = config.runnerAdvancementKey();
        Advancement advancement = ManhuntGoalMenu.resolveAdvancement(key);
        AdvancementDisplay display = advancement == null ? null : advancement.getDisplay();
        List<String> lore = List.of("<gray>Current key: <white>" + key,
                "<dark_gray>Click to pick from seven curated advancements.");
        if (display != null) {
            return ManhuntGoalMenu.styledIcon(display.icon(), "<gold>Choose the Runners' goal", lore);
        }
        return Icons.of(Material.KNOWLEDGE_BOOK, "<gold>Choose the Runners' goal", lore);
    }

    private ItemStack flagIcon(boolean on, String name, String description) {
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "<gold>" + name + ": " + (on ? "<green>on" : "<red>off"),
                "<gray>" + description, "<dark_gray>Click to toggle.");
    }

    public String describe() {
        return "how a hunt is set up: the win conditions, the map, what a death costs, and what "
                + "happens when it is over";
    }
}
