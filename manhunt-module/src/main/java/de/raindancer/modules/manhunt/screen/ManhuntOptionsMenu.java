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
 * Five settings worth a click without leaving Manhunt's own menus — everything else in
 * {@link ManhuntSettings} stays reachable only through the server's generic {@code /settings}
 * command, which already renders every field of it with an icon. Each click here cycles the exact
 * same {@code SettingsStore} that command edits, through {@code SettingsStore.cycle}, so there is
 * exactly one place any of these values can actually change — this menu is a shortcut to it, not a
 * second copy of it.
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

    private ItemStack flagIcon(boolean on, String name, String description) {
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "<gold>" + name + ": " + (on ? "<green>on" : "<red>off"),
                "<gray>" + description, "<dark_gray>Click to toggle.");
    }

    public String describe() {
        return "five curated Manhunt settings, cycled here or in full at /settings";
    }
}
