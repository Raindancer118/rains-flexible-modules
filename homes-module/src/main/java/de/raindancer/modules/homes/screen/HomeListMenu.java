package de.raindancer.modules.homes.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.homes.HomeServices;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.HomeIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every home this player has: what bare {@code /home} opens.
 *
 * <h2>Why bare {@code /home} is the list and not a guess</h2>
 * Because guessing is worse than asking. {@code /home} could reasonably mean "the one called home", and
 * on a server where somebody called theirs something else it would then be a command that always fails
 * for them. The list is the one answer that is right for everybody, and the home called {@code home} is
 * one click away on it.
 */
public final class HomeListMenu extends PaginatedMenu<Home> implements IHomeScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final HomeServices services;

    public HomeListMenu(HomeServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        // Never the brand: it is already prefixed, so "Homes" here would render as "Homes » Homes".
        return MINI.deserialize("<dark_gray>Your homes");
    }

    @Override
    public String breadcrumb() {
        return "Your homes";
    }

    @Override
    protected List<Home> entries() {
        return services.homes().of(viewer.getUniqueId());
    }

    /**
     * Nobody has any homes to begin with, so this is the first thing a new player sees.
     *
     * <p>It says what to do rather than only that there is nothing, and the button does it — a list
     * that names the way out has to be able to act on it, or the sentence is decoration.
     */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>You have no homes yet",
                "<gray>Stand where you want one and use",
                "<white>/sethome<gray>, or click here to be told how.",
                "",
                "<dark_gray>You may have " + services.keeping().describeLimitFor(viewer) + ".");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        viewer.closeInventory();
        services.messages().send(viewer, "homes.how-to-set-one");
    }

    @Override
    protected ItemStack icon(Home home) {
        List<String> lore = new ArrayList<>();
        if (home.isReachable()) {
            lore.add("<dark_gray>" + home.world() + " " + home.coordinates());
        } else {
            // Said in words as well as by the barrier the icon becomes: a home in a world that is
            // unloaded for maintenance is not a home to delete.
            lore.add("<red>Its world is not loaded right now.");
        }
        lore.add("");
        if (services.config().warmup() > 0) {
            lore.add("<gray>Click to go. Stand still for "
                    + services.config().warmup() + "s.");
        } else {
            lore.add("<gray>Click to go.");
        }
        lore.add("<gray>Right click to rename it, change its");
        lore.add("<gray>block, or delete it.");

        return Icons.of(HomeIcons.materialFor(home), "<white>" + home.name(), lore);
    }

    /**
     * Left click goes, right click edits.
     *
     * <p>Right rather than shift-right, which is what the old plugin used for deleting outright — one
     * modifier away from going there, with no confirmation. Editing is the safe thing to put behind the
     * easier gesture, and the delete now lives behind a page of its own.
     */
    @Override
    protected void onClick(Home home, InventoryClickEvent event) {
        if (event.isRightClick()) {
            new HomeEditMenu(services, viewer, this, home.name()).open();
            return;
        }
        // Closed first: going home can take a few seconds of standing still, and a window open over it
        // is a window the player has to shut before they can be seen to have moved.
        viewer.closeInventory();
        services.travelling().go(viewer, home);
    }

    /** The counter, which is the one thing on the page that is not a home. */
    @Override
    protected void decorate() {
        super.decorate();
        int have = services.homes().count(viewer.getUniqueId());
        toolbar(4, Icons.of(Material.RED_BED, "<white>Your homes",
                        "<gray>" + have + " of " + services.keeping().describeLimitFor(viewer)
                                + " used.",
                        "<dark_gray>A permission can raise that; nothing lowers it."),
                click -> {
                });
    }

    @Override
    protected List<String> helpLines() {
        // Core draws these as a book. Generated from the settings that are loaded, so the page cannot
        // come to describe a wait this server does not have.
        return services.messages().lines("homes.manual.using",
                        "warmup", services.config().warmup(),
                        "cooldown", services.config().cooldown(),
                        "limit", services.keeping().describeLimitFor(viewer))
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "every home this player has";
    }
}
