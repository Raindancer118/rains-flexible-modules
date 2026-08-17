package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.Wall;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * One wall's page — every action from the plan's table, and its exact opposite, reachable from a
 * button: build/teardown, sharp/rounded corners, its gates, rename, delete.
 */
public final class WallEditMenu extends Menu {

    private final WallsRoadsServices services;
    private final Wall wall;

    public WallEditMenu(WallsRoadsServices services, Player viewer, Wall wall, Menu parent) {
        super(viewer, services.brand(), parent, 4);
        this.services = services;
        this.wall = wall;
    }

    @Override
    protected Component title() {
        return Component.text(wall.name());
    }

    @Override
    protected void render() {
        boolean mayManage = wall.owner().equals(viewer.getUniqueId())
                || viewer.hasPermission(de.raindancer.modules.wallsroads.util.PermissionNodes.MANAGE_ANY);
        boolean built = wall.isBuilt();

        band(MenuLayout.WHO, 1, mayManage,
                Icons.of(built ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                        built ? "<green>Standing" : "<gray>Not built",
                        "<gray>" + wall.outline().vertices().size() + " corners, "
                                + wall.height() + " tall, " + wall.thickness() + " thick",
                        "",
                        "<yellow>Click <gray>to " + (built ? "take it down" : "build it")),
                "The owner's to change",
                click -> {
                    if (built) {
                        services.service().teardownWall(wall, this::refresh);
                    } else {
                        services.service().buildWall(wall, this::refresh);
                    }
                });

        band(MenuLayout.WHO, 3, mayManage,
                Icons.of(wall.material(), "<white>Material",
                        "<gray>" + wall.material().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                        "",
                        "<yellow>Click <gray>to choose another"),
                "The owner's to change",
                click -> new ItemChooser(viewer, services.brand(), this, "Wall material", chosen -> {
                    wall.material(chosen);
                    services.storage().saveWall(wall);
                    reopenPage();
                }).open());

        band(MenuLayout.WHO, 5, mayManage,
                Icons.of(wall.cornerStyle().isRounded() ? Material.SMOOTH_STONE_SLAB : Material.SMOOTH_STONE,
                        wall.cornerStyle().isRounded()
                                ? "<white>Rounded corners <gray>(" + wall.cornerStyle().radius() + " blocks)"
                                : "<white>Sharp corners",
                        "",
                        "<yellow>Click <gray>to switch to "
                                + (wall.cornerStyle().isRounded() ? "sharp" : "rounded")),
                "The owner's to change",
                click -> {
                    if (wall.cornerStyle().isRounded()) {
                        wall.cornerStyle(CornerStyle.SHARP);
                        services.service().reshapeWall(wall, this::refresh);
                    } else {
                        new AmountChooser(viewer, services.brand(), this, "Corner radius",
                                6, 1, 32, radius -> {
                            wall.cornerStyle(CornerStyle.rounded(radius));
                            services.service().reshapeWall(wall, this::refresh);
                        }).open();
                    }
                });

        band(MenuLayout.WHO, 7, mayManage,
                Icons.of(Material.OAK_FENCE_GATE, "<white>Gates",
                        "<gray>" + wall.gates().size() + " cut so far",
                        "",
                        "<yellow>Click <gray>to open, seal or reopen one"),
                "The owner's to change",
                click -> new GateListMenu(services, viewer, wall, this).open());

        toolbar(2, Icons.of(Material.NAME_TAG, "<white>Rename",
                        "<gray>Current: " + wall.name(),
                        "",
                        "<yellow>Click <gray>then type the new name in chat"),
                click -> {
                    viewer.closeInventory();
                    askRename();
                });

        danger(Icons.of(Material.TNT, "<white>Remove this wall",
                        "<red>Tears it back down and forgets it.",
                        "",
                        "<dark_gray>This cannot be undone."),
                click -> new ConfirmScreen(services, viewer, this,
                        "Remove " + wall.name() + "?",
                        List.of("It will be torn back down first,", "restoring the ground beneath it."),
                        () -> services.service().deleteWall(wall, () ->
                                services.messages().send(viewer, "wallsroads.wall.removed", "name", wall.name())))
                        .open());
    }

    /** A rename prompt, chat-typed — the same pattern claims-module's own naming prompt already uses. */
    private void askRename() {
        ChatPrompts prompts = services.core().prompts();
        services.messages().send(viewer, "wallsroads.wall.ask-name");
        prompts.ask(viewer.getUniqueId(), "WallsRoads", Duration.ofSeconds(30), typed ->
                de.raindancer.core.platform.util.Scheduling.entity(services.plugin(), viewer, () -> {
                    services.service().renameWall(wall, typed);
                    new WallEditMenu(services, viewer, wall, parent()).open();
                }), () -> { });
    }

    private void reopenPage() {
        new WallEditMenu(services, viewer, wall, parent()).open();
    }
}
