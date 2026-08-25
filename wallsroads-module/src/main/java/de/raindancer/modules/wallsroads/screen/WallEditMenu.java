package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.model.WallProfile;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One wall's page — every action, and its exact opposite, one click away: build/teardown, its
 * material, sharp or rounded corners, what kind of wall it is, its gates, who may work them.
 */
public final class WallEditMenu extends Menu {

    private final WallsRoadsServices services;
    private final Wall wall;

    public WallEditMenu(WallsRoadsServices services, Player viewer, Wall wall, Menu parent) {
        super(viewer, services.brand(), parent, 5);
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
                || viewer.hasPermission(PermissionNodes.MANAGE_ANY);
        boolean built = wall.isBuilt();

        band(MenuLayout.WHO, 1, mayManage,
                Icons.of(built ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                        built ? "<green>Standing" : "<gray>Not built",
                        "<gray>" + wall.outline().vertices().size() + " corners, "
                                + wall.height() + " tall, " + wall.thickness() + " thick",
                        built ? "<gray>Taking it down puts back what was underneath."
                                : "<gray>About " + estimate() + " blocks of material.",
                        "",
                        "<yellow>Click <gray>to " + (built ? "take it down" : "build it")),
                "The owner's to change",
                click -> {
                    if (built) {
                        services.service().teardownWall(wall, this::refresh);
                    } else {
                        services.service().buildWall(wall, viewer, this::refresh);
                    }
                });

        band(MenuLayout.WHO, 3, mayManage,
                Icons.of(wall.material(), "<white>Material",
                        "<gray>" + pretty(wall.material().name()),
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
                        "<yellow>Click <gray>to open, shut, seal or reopen one"),
                "The owner's to change",
                click -> new GateListMenu(services, viewer, wall, this).open());

        // What kind of wall it is, and who its gates answer to.
        band(MenuLayout.RULES, 2, mayManage,
                Icons.of(Material.STONE_BRICK_WALL, "<white>Kind of wall",
                        "<gray>" + describe(wall.profile()),
                        "",
                        "<yellow>Click <gray>to cycle: plain, town, fortress",
                        "<gray>A standing wall is rebuilt to match."),
                "The owner's to change",
                click -> {
                    wall.profile(nextProfile(wall.profile()));
                    services.storage().saveWall(wall);
                    services.service().reshapeWall(wall, this::refresh);
                });

        band(MenuLayout.RULES, 4, mayManage,
                Icons.of(wall.gatesOpenToEveryone() ? Material.OAK_DOOR : Material.IRON_DOOR,
                        wall.gatesOpenToEveryone()
                                ? "<white>Anybody may work the gates"
                                : "<white>Only you may work the gates",
                        "",
                        "<yellow>Click <gray>to switch"),
                "The owner's to change",
                click -> {
                    wall.gatesOpenToEveryone(!wall.gatesOpenToEveryone());
                    services.storage().saveWall(wall);
                    refresh();
                });

        boolean curfewAllowed = services.config().nightCurfewAllowed();
        band(MenuLayout.RULES, 6, mayManage && curfewAllowed,
                Icons.of(wall.closesAtNight() ? Material.CLOCK : Material.LIGHT_GRAY_DYE,
                        wall.closesAtNight() ? "<white>Gates shut at dusk" : "<gray>Gates stay as they are left",
                        curfewAllowed ? "<gray>They open again at dawn."
                                : "<red>The server has turned this off.",
                        "",
                        "<yellow>Click <gray>to switch"),
                curfewAllowed ? "The owner's to change" : "The server has turned this off",
                click -> {
                    wall.closesAtNight(!wall.closesAtNight());
                    services.storage().saveWall(wall);
                    refresh();
                });

        band(MenuLayout.LAND, 2, mayManage,
                Icons.of(Material.BRICKS, "<white>How thick",
                        "<gray>" + wall.thickness() + " blocks across",
                        "<dark_gray>The wall-walk on top is as wide as this,",
                        "<dark_gray>so one block is a fence and three is a wall.",
                        "",
                        "<yellow>Click <gray>to add one, <yellow>right click <gray>to take one away",
                        "<gray>A standing wall is rebuilt to match."),
                "The owner's to change",
                click -> {
                    int wanted = click.isRightClick() ? wall.thickness() - 1 : wall.thickness() + 1;
                    wall.thickness(Math.max(1, Math.min(9, wanted)));
                    services.storage().saveWall(wall);
                    services.service().reshapeWall(wall, this::refresh);
                });

        band(MenuLayout.LAND, 4, mayManage,
                Icons.of(Material.LADDER, "<white>How tall",
                        "<gray>" + wall.height() + " blocks, before the parapet",
                        "",
                        "<yellow>Click <gray>to add one, <yellow>right click <gray>to take one away",
                        "<gray>A standing wall is rebuilt to match."),
                "The owner's to change",
                click -> {
                    int wanted = click.isRightClick() ? wall.height() - 1 : wall.height() + 1;
                    wall.bounds(wall.minY(), Math.max(2, Math.min(64, wanted)));
                    services.storage().saveWall(wall);
                    services.service().reshapeWall(wall, this::refresh);
                });

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

    /** Plain → town → fortress → plain: three named kinds beat eight materials to choose. */
    private static WallProfile nextProfile(WallProfile current) {
        if (!current.battlements()) {
            return WallProfile.town();
        }
        return current.hasTowers() ? WallProfile.simple() : WallProfile.fortress();
    }

    private static String describe(WallProfile profile) {
        if (profile.hasTowers()) {
            return "A fortress — footed, crenellated, with towers";
        }
        return profile.battlements() ? "A town wall — footed, crenellated, with a walkway" : "A plain wall";
    }

    private String estimate() {
        Map<String, Integer> cost = services.service().estimateWall(wall);
        return String.valueOf(cost.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static String pretty(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void askRename() {
        ChatPrompts prompts = services.core().prompts();
        services.messages().send(viewer, "wallsroads.wall.ask-name");
        prompts.ask(viewer.getUniqueId(), "WallsRoads", Duration.ofSeconds(30), typed ->
                Scheduling.entity(services.plugin(), viewer, () -> {
                    services.service().renameWall(wall, typed);
                    new WallEditMenu(services, viewer, wall, parent()).open();
                }), () -> { });
    }

    private void reopenPage() {
        new WallEditMenu(services, viewer, wall, parent()).open();
    }
}
