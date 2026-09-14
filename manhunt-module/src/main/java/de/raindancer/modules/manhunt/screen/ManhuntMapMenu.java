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
 * The ground a hunt is played on: which world, whether it is thrown away and made again before each
 * run, and what seed it is made from.
 *
 * <h2>Why this is a page and not four more buttons on {@link ManhuntOptionsMenu}</h2>
 * Because that is what the claim screens do, and the options page was already over its slots. Four
 * settings about one subject are a category, and a category is a door — the alternative was buttons
 * pressed up against each other, which the GUI conventions forbid for the plain reason that a wall of
 * adjacent icons cannot be read.
 *
 * <p>Two of these were not on any screen at all before, only in {@code /settings}: the world's name and
 * the fixed seed. A seed policy of {@code FIXED} with no way to see or set the seed is a button that
 * does nothing anybody can observe.
 */
public final class ManhuntMapMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntMapMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>The map");
    }

    @Override
    public String breadcrumb() {
        return "Map";
    }

    @Override
    protected void render() {
        ManhuntSettings config = services.config();

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.FILLED_MAP,
                "<gold>The world a hunt runs in",
                "<gray>Which world, whether it is remade,",
                "<gray>and what it is made from."));

        band(MenuLayout.WHO, 2, Icons.of(Material.GRASS_BLOCK,
                        "<white>World: <green>" + worldName(config),
                        "<gray>The world a hunt takes place in.",
                        "<dark_gray>Set it in /settings — a world name is",
                        "<dark_gray>typed, and a wrong one is a hunt that never starts."));

        band(MenuLayout.WHO, 6, flagIcon(config.resetOnStart(), "Remake it each run",
                        "The world is thrown away and generated again before every hunt."),
                click -> cycle("reset-on-start"));

        band(MenuLayout.LAND, 2, seedIcon(config), click -> cycle("seed-choice"));

        band(MenuLayout.LAND, 6, Icons.of(Material.IRON_NUGGET,
                        "<white>Fixed seed: <green>" + config.seedValue(),
                        "<gray>The number the map is made from when the",
                        "<gray>policy beside this is FIXED.",
                        "<dark_gray>Set it in /settings — a seed is a number",
                        "<dark_gray>nobody could reach by clicking."));
    }

    private String worldName(ManhuntSettings config) {
        return config.worldName() == null || config.worldName().isBlank() ? "not set" : config.worldName();
    }

    private void cycle(String key) {
        services.store().cycle(key);
        services.store().save();
        refresh();
    }

    private ItemStack seedIcon(ManhuntSettings config) {
        boolean random = config.seedChoice() == ManhuntSettings.SeedChoice.RANDOM;
        return Icons.of(random ? Material.MAGMA_CREAM : Material.IRON_NUGGET,
                "<white>Seed policy: <green>" + config.seedChoice(),
                random
                        ? "<gray>A fresh map every hunt."
                        : "<gray>The same map every hunt, from the seed below.",
                "<dark_gray>Click to cycle.");
    }

    private ItemStack flagIcon(boolean on, String name, String description) {
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>" + name + ": " + (on ? "<green>on" : "<red>off"),
                "<gray>" + description, "<dark_gray>Click to toggle.");
    }

    public String describe() {
        return "which world a hunt runs in, whether it is remade each time, and the seed it is made from";
    }
}
