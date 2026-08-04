package de.raindancer.modules.homes.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.homes.HomeServices;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.HomeIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * One home, and everything that can be done to it.
 *
 * <h2>Why the home is held by name rather than as a value</h2>
 * Because everything on this page changes it, and a page holding the home it was opened with would
 * redraw the version from before the click. Looked up on every draw instead, so it is always showing
 * what is actually stored — and a home deleted from underneath it says so rather than offering buttons
 * for something that has gone.
 */
public final class HomeEditMenu extends Menu implements IHomeScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long somebody has to type a new name before the question is dropped. */
    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final HomeServices services;
    private final String name;

    public HomeEditMenu(HomeServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.name = name;
    }

    private Home home() {
        return services.homes().find(viewer.getUniqueId(), name).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + name);
    }

    @Override
    public String breadcrumb() {
        return name;
    }

    @Override
    protected void render() {
        Home home = home();
        if (home == null) {
            // Deleted from under this page — from a command, or a second window. Offering buttons for
            // it would be offering buttons for nothing.
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This home is gone",
                    "<gray>It was deleted while this page was open."));
            return;
        }

        band(MenuLayout.WHO, 1, Icons.of(Material.ENDER_PEARL, "<white>Go here",
                        home.isReachable()
                                ? "<dark_gray>" + home.world() + " " + home.coordinates()
                                : "<red>Its world is not loaded right now.",
                        "",
                        "<gray>Click to go. This closes the menu."),
                click -> {
                    viewer.closeInventory();
                    services.travelling().go(viewer, home);
                });

        band(MenuLayout.WHO, 3, Icons.of(Material.NAME_TAG, "<white>Rename it",
                        "<gray>Called <white>" + home.name() + "<gray> now.",
                        "",
                        "<gray>Click to type a new name.",
                        "<dark_gray>" + services.names().describe() + "."),
                click -> askForANewName());

        Material icon = HomeIcons.materialFor(home);
        band(MenuLayout.WHO, 5, Icons.of(icon, "<white>What it looks like",
                        "<gray>" + HomeIcons.readable(icon)
                                + (home.hasIcon() ? "" : " <dark_gray>(from its world)"),
                        "",
                        "<gray>Click to pick a block.",
                        "<gray>Right click to go back to the world's own."),
                click -> {
                    if (click.isRightClick()) {
                        services.keeping().setIcon(viewer, name, null);
                        refresh();
                        return;
                    }
                    new HomeIconMenu(services, viewer, this, name).open();
                });

        // The danger slot, and the only irreversible thing on the page — so it asks first.
        danger(Icons.of(Material.BARRIER, "<red>Delete this home",
                        "<gray>The place, its name and its block all go.",
                        "",
                        "<gray>You will be asked first."),
                click -> new ConfirmScreen(services, viewer, this,
                        "<red>Delete " + home.name() + "?",
                        List.of("<gray>The home goes, with the block you",
                                "<gray>chose for it. You can set another",
                                "<gray>anywhere, but not this one back."),
                        () -> {
                            services.keeping().delete(viewer, name);
                            // Back two pages: the page this came from is about a home that is gone.
                            if (parent() != null && parent().parent() != null) {
                                parent().parent().open();
                            } else if (parent() != null) {
                                parent().open();
                            } else {
                                viewer.closeInventory();
                            }
                        }).open());
    }

    /**
     * Asks in chat, which is the one thing a menu genuinely cannot ask.
     *
     * <p>A name has nothing to enumerate, so there is no chooser to open. Everything with a set of
     * answers — the block it shows as — is a chooser, per the module's own grammar.
     */
    private void askForANewName() {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "homes", TO_ANSWER,
                answer -> {
                    if (answer != null && answer.equalsIgnoreCase("cancel")) {
                        services.messages().send(viewer, "homes.left-as-it-is");
                        open();
                        return;
                    }
                    services.keeping().rename(viewer, name, answer)
                            .ifPresentOrElse(
                                    // Rebuilt rather than reopened: this page is about a name that has
                                    // just changed, and its own title would be the old one.
                                    renamed -> new HomeEditMenu(services, viewer, parent(),
                                            renamed.name()).open(),
                                    this::open);
                },
                this::open);
        if (!asking) {
            // Somebody else is already asking them something. Saying so beats overwriting it, which
            // would leave the other plugin waiting for an answer it never gets.
            services.messages().send(viewer, "homes.busy");
            open();
            return;
        }
        services.messages().send(viewer, "homes.ask-name", "name", name);
    }

    @Override
    public String describe() {
        return "one home, and everything that can be done to it";
    }
}
