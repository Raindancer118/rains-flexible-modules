package de.raindancer.modules.manhunt.screen;

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

        band(MenuLayout.WHO, 1, runnerWinIcon(config), click -> cycle("runner-win"));
        band(MenuLayout.WHO, 2, hunterWinIcon(config), click -> cycle("hunter-win"));
        band(MenuLayout.WHO, 3, flagIcon(config.resetOnStart(), "Reset the map on start",
                        "Throws the configured world away and makes it again before each run."),
                click -> cycle("reset-on-start"));
        band(MenuLayout.WHO, 4, flagIcon(config.closeWhitelistOnStart(), "Close the whitelist on start",
                        "Snapshots everybody online as whitelisted the moment the countdown ends."),
                click -> cycle("close-whitelist-on-start"));
        band(MenuLayout.WHO, 5, seedIcon(config), click -> cycle("seed-choice"));
        band(MenuLayout.WHO, 6, goalIcon(config), click -> new ManhuntGoalMenu(services, viewer, this).open());

        // What a death costs.
        band(MenuLayout.RULES, 1, Icons.of(Material.RED_CANDLE, "<gold>Shorter countdown",
                        "<gray>Now <white>" + config.countdownSecondsClamped() + "<gray> second(s) before a hunt starts.",
                        "<dark_gray>Click to take one off."),
                click -> bump("countdown-seconds", config.countdownSecondsClamped(), -1, 0, 60));
        band(MenuLayout.RULES, 2, Icons.of(Material.GREEN_CANDLE, "<gold>Longer countdown",
                        "<gray>Now <white>" + config.countdownSecondsClamped() + "<gray> second(s) before a hunt starts.",
                        "<dark_gray>Click to add one."),
                click -> bump("countdown-seconds", config.countdownSecondsClamped(), +1, 0, 60));
        band(MenuLayout.RULES, 4, deathRuleIcon(config), click -> cycle("runner-death-rule"));
        band(MenuLayout.RULES, 6, Icons.of(Material.TOTEM_OF_UNDYING, "<gold>Lives per Runner: "
                                + config.runnerLivesClamped(),
                        "<gray>Only used when the rule beside this is LIVES.",
                        "<dark_gray>Click to add one, right-click to take one off."),
                click -> bump("runner-lives", config.runnerLivesClamped(),
                        click.isRightClick() ? -1 : +1, 1, 10));
        band(MenuLayout.RULES, 7, flagIcon(config.eliminatedSpectate(), "Out means watching",
                        "An eliminated Runner is put into Spectator rather than left standing."),
                click -> cycle("eliminated-spectate"));

        // What happens when it is over.
        band(MenuLayout.LAND, 1, Icons.of(Material.CLOCK, "<gold>A dead Hunter waits: "
                                + config.hunterRespawnDelaySecondsClamped() + "s",
                        "<gray>Held in Spectator this long after dying.",
                        "<dark_gray>Click to add five, right-click to take five off."),
                click -> bump("hunter-respawn-delay-seconds", config.hunterRespawnDelaySecondsClamped(),
                        click.isRightClick() ? -5 : +5, 0, 300));
        band(MenuLayout.LAND, 3, flagIcon(config.returnToLobbyOnFinish(), "Back to the lobby",
                        "Everybody is returned to the waiting lobby once a hunt ends."),
                click -> cycle("return-to-lobby-on-finish"));
        band(MenuLayout.LAND, 5, flagIcon(config.keepRosterOnFinish(), "Keep the sides",
                        "The two rosters survive a hunt, so the next one starts with the same sides."),
                click -> cycle("keep-roster-on-finish"));
    }

    /** Nudges a numeric setting, clamped to its own {@code @Range} first — {@code SettingsStore.set}
     *  refuses an out-of-range value rather than clamping it. */
    private void bump(String key, int current, int by, int least, int most) {
        int wanted = Math.max(least, Math.min(most, current + by));
        services.store().set(key, Integer.toString(wanted));
        services.store().save();
        refresh();
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

    private ItemStack seedIcon(ManhuntSettings config) {
        boolean random = config.seedChoice() == ManhuntSettings.SeedChoice.RANDOM;
        return Icons.of(random ? Material.MAGMA_CREAM : Material.IRON_NUGGET,
                "<gold>Seed policy: " + config.seedChoice(),
                "<gray>A fixed seed replays the same map; random makes a fresh one.",
                "<dark_gray>Click to cycle.");
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
