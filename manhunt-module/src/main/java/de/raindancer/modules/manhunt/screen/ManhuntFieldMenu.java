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
 * What a hunt is like to be in: what it says out loud, who hears you when you talk, the rules it
 * borrows for its own length, and whether anybody may watch from outside it.
 *
 * <h2>Why these four belong on one page</h2>
 * They are the settings a player feels rather than the ones that decide who wins — narration, side
 * chat, friendly fire and the borrowed game rules all change what an hour inside a hunt is like, and
 * none of them change what a hunt is for. {@link ManhuntOptionsMenu} keeps the ones that do.
 */
public final class ManhuntFieldMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntFieldMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>In the field");
    }

    @Override
    public String breadcrumb() {
        return "Field";
    }

    @Override
    protected void render() {
        ManhuntSettings config = services.config();

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BELL, "<gold>In the field",
                "<gray>What a hunt says, who hears you,",
                "<gray>and the rules it borrows while it runs."));

        // What is said out loud.
        band(MenuLayout.WHO, 1, flagIcon(config.narrateDimensions(), "Announce dimensions",
                        "A Runner reaching the Nether or the End is said out loud."),
                click -> cycle("narrate-dimensions"));
        band(MenuLayout.WHO, 3, flagIcon(config.narrateDeaths(), "Announce deaths",
                        "Deaths and eliminations are told to both sides, not only the victim."),
                click -> cycle("narrate-deaths"));
        band(MenuLayout.WHO, 5, flagIcon(config.narrateTimeLeft(), "Call the clock",
                        "Five minutes, one minute and ten seconds left, each said once."),
                click -> cycle("narrate-time-left"));
        band(MenuLayout.WHO, 7, flagIcon(config.narrateDragon(), "Call the dragon",
                        "Half and a quarter of the dragon's health, each said once."),
                click -> cycle("narrate-dragon"));

        // Who hears you.
        band(MenuLayout.RULES, 1, flagIcon(config.sideChat(), "Chat to your own side",
                        "While a hunt runs, only your own side hears what you type."),
                click -> cycle("side-chat"));
        band(MenuLayout.RULES, 3, Icons.of(Material.NAME_TAG, "<gold>Break-out prefix: "
                                + prefixLabel(config),
                        "<gray>A message starting with this is heard by everybody.",
                        "<dark_gray>Set it with /settings — a prefix is typed, not clicked."),
                click -> { });
        band(MenuLayout.RULES, 5, flagIcon(config.coordinateSharing(), "Share your position",
                        "Whether /manhunt here tells your own side where you are."),
                click -> cycle("coordinate-sharing"));
        band(MenuLayout.RULES, 7, flagIcon(config.spectatorsAllowed(), "Anybody may watch",
                        "Whether /manhunt spectate lets an outsider watch a running hunt."),
                click -> cycle("spectators-allowed"));

        // The rules it borrows.
        band(MenuLayout.LAND, 1, flagIcon(config.friendlyFire(), "Friendly fire",
                        "Off: a side cannot hurt its own while a hunt is running."),
                click -> cycle("friendly-fire"));
        band(MenuLayout.LAND, 3, overrideIcon("Keep inventory", config.keepInventoryDuringHunt(),
                        Material.CHEST),
                click -> cycle("keep-inventory-during-hunt"));
        band(MenuLayout.LAND, 5, overrideIcon("Natural regeneration",
                        config.naturalRegenerationDuringHunt(), Material.GOLDEN_APPLE),
                click -> cycle("natural-regeneration-during-hunt"));
        band(MenuLayout.LAND, 7, Icons.of(Material.IRON_SWORD, "<gold>Difficulty: "
                                + config.difficultyDuringHunt(),
                        "<gray>Borrowed for the length of a hunt and put back afterwards.",
                        "<dark_gray>Click to cycle."),
                click -> cycle("difficulty-during-hunt"));
    }

    private void cycle(String key) {
        services.store().cycle(key);
        services.store().save();
        refresh();
    }

    private static String prefixLabel(ManhuntSettings config) {
        String prefix = config.sideChatGlobalPrefix();
        return prefix == null || prefix.isEmpty() ? "<red>none" : "<white>" + prefix;
    }

    /** A three-way borrow, which is a cycle and not a toggle — so it says which of the three it is on. */
    private ItemStack overrideIcon(String name, ManhuntSettings.RuleOverride override, Material icon) {
        String what = switch (override) {
            case UNCHANGED -> "<gray>The world's own setting is left alone.";
            case ON -> "<gray>Switched on for the length of a hunt, then put back.";
            case OFF -> "<gray>Switched off for the length of a hunt, then put back.";
        };
        return Icons.of(override == ManhuntSettings.RuleOverride.UNCHANGED ? Material.GRAY_DYE : icon,
                "<gold>" + name + ": " + override, what, "<dark_gray>Click to cycle.");
    }

    private ItemStack flagIcon(boolean on, String name, String description) {
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "<gold>" + name + ": " + (on ? "<green>on" : "<red>off"),
                "<gray>" + description, "<dark_gray>Click to toggle.");
    }

    public String describe() {
        return "what a hunt says, who hears you, and the rules it borrows while it runs";
    }
}
