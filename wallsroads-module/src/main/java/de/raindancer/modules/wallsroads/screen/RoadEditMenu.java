package de.raindancer.modules.wallsroads.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * One road's page — build/teardown, material, width, elevation mode, its signs, rename, delete —
 * each action from the plan's table with its exact opposite one click away.
 */
public final class RoadEditMenu extends Menu {

    private final WallsRoadsServices services;
    private final RoadPath road;

    public RoadEditMenu(WallsRoadsServices services, Player viewer, RoadPath road, Menu parent) {
        super(viewer, services.brand(), parent, 5);
        this.services = services;
        this.road = road;
    }

    @Override
    protected Component title() {
        return Component.text(road.name());
    }

    @Override
    protected void render() {
        boolean mayManage = road.owner().equals(viewer.getUniqueId())
                || viewer.hasPermission(de.raindancer.modules.wallsroads.util.PermissionNodes.MANAGE_ANY);
        boolean built = road.isBuilt();

        band(MenuLayout.WHO, 1, mayManage,
                Icons.of(built ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                        built ? "<green>Built" : "<gray>Not built",
                        "<gray>" + String.format(java.util.Locale.ROOT, "%.0f", road.path().length())
                                + " blocks long, " + (int) road.width() + " wide",
                        "",
                        "<yellow>Click <gray>to " + (built ? "take it up" : "build it")),
                "The owner's to change",
                click -> {
                    if (built) {
                        services.service().teardownRoad(road, this::refresh);
                    } else {
                        services.service().buildRoad(road, viewer, this::refresh);
                    }
                });

        band(MenuLayout.WHO, 3, mayManage,
                Icons.of(road.material(), "<white>Material",
                        "<gray>" + road.material().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                        "",
                        "<yellow>Click <gray>to choose another"),
                "The owner's to change",
                click -> new ItemChooser(viewer, services.brand(), this, "Road material", chosen -> {
                    road.material(chosen);
                    services.storage().saveRoad(road);
                    reopenPage();
                }).open());

        band(MenuLayout.WHO, 5, mayManage,
                Icons.of(Material.MINECART, "<white>Width", "<gray>" + (int) road.width() + " blocks",
                        "", "<yellow>Click <gray>to change"),
                "The owner's to change",
                click -> new AmountChooser(viewer, services.brand(), this, "Road width",
                        (int) road.width(), 1, 32, width -> {
                    road.width(width);
                    services.storage().saveRoad(road);
                    reopenPage();
                }).open());

        boolean followsTerrain = road.elevationMode() == ElevationMode.FOLLOW_TERRAIN;
        band(MenuLayout.WHO, 7, mayManage,
                Icons.of(followsTerrain ? Material.GRASS_BLOCK : Material.SMOOTH_STONE_SLAB,
                        followsTerrain ? "<white>Follows the terrain" : "<white>Fixed height",
                        "",
                        "<yellow>Click <gray>to switch to "
                                + (followsTerrain ? "a fixed height" : "following the terrain")),
                "The owner's to change",
                click -> {
                    road.elevationMode(followsTerrain ? ElevationMode.FIXED_Y : ElevationMode.FOLLOW_TERRAIN);
                    services.storage().saveRoad(road);
                    reopenPage();
                });

        // What the road is made of besides its paving — the difference between a track and a highway.
        band(MenuLayout.RULES, 2, mayManage,
                Icons.of(Material.LANTERN, "<white>Kind of road",
                        "<gray>" + describe(road.profile()),
                        "",
                        "<yellow>Click <gray>to cycle: plain, lit, grand",
                        "<gray>A built road is relaid to match."),
                "The owner's to change",
                click -> {
                    road.profile(nextProfile(road.profile()));
                    services.storage().saveRoad(road);
                    if (road.isBuilt()) {
                        services.service().teardownRoad(road, () ->
                                services.service().buildRoad(road, viewer, this::refresh));
                    } else {
                        refresh();
                    }
                });

        boolean hasSigns = !road.signs().isEmpty();
        band(MenuLayout.RULES, 4, mayManage,
                Icons.of(hasSigns ? Material.OAK_SIGN : Material.BARRIER,
                        hasSigns ? "<white>Signs — " + road.signs().size() + " placed" : "<gray>No signs",
                        "",
                        hasSigns ? "<yellow>Click <gray>to read and reword them"
                                : "<yellow>Click <gray>to put them up",
                        hasSigns ? "<yellow>Right click <gray>to take them all down" : ""),
                "The owner's to change",
                click -> {
                    if (!hasSigns) {
                        services.service().placeSigns(road);
                        refresh();
                        return;
                    }
                    if (click.isRightClick()) {
                        services.service().removeSigns(road);
                        refresh();
                        return;
                    }
                    new SignListMenu(services, viewer, road, this).open();
                });

        toolbar(6, Icons.of(Material.NAME_TAG, "<white>Rename",
                        "<gray>Current: " + road.name(),
                        "",
                        "<yellow>Click <gray>then type the new name in chat"),
                click -> {
                    viewer.closeInventory();
                    askRename();
                });

        danger(Icons.of(Material.TNT, "<white>Remove this road",
                        "<red>Tears it back up and forgets it.",
                        "",
                        "<dark_gray>This cannot be undone."),
                click -> new ConfirmScreen(services, viewer, this,
                        "Remove " + road.name() + "?",
                        List.of("It will be taken up first,", "restoring the ground beneath it."),
                        () -> services.service().deleteRoad(road, () ->
                                services.messages().send(viewer, "wallsroads.road.removed", "name", road.name())))
                        .open());
    }

    /** Plain → lit → grand → plain. Three named kinds, not nine materials to pick one at a time. */
    private static de.raindancer.modules.wallsroads.model.RoadProfile nextProfile(
            de.raindancer.modules.wallsroads.model.RoadProfile current) {
        if (!current.isLit()) {
            return de.raindancer.modules.wallsroads.model.RoadProfile.lit();
        }
        return current.lamp() == Material.SEA_LANTERN
                ? de.raindancer.modules.wallsroads.model.RoadProfile.plain()
                : de.raindancer.modules.wallsroads.model.RoadProfile.grand();
    }

    private static String describe(de.raindancer.modules.wallsroads.model.RoadProfile profile) {
        if (!profile.isLit()) {
            return "A track — paving, and nothing else";
        }
        return profile.lamp() == Material.SEA_LANTERN
                ? "A highway — stone kerbs, sea lanterns, stone railings"
                : "A made road — kerbs and lanterns";
    }

    private String estimate() {
        return String.valueOf(services.service().estimateRoad(road).values().stream()
                .mapToInt(Integer::intValue).sum());
    }

    private void askRename() {
        ChatPrompts prompts = services.core().prompts();
        services.messages().send(viewer, "wallsroads.road.ask-name");
        prompts.ask(viewer.getUniqueId(), "WallsRoads", Duration.ofSeconds(30), typed ->
                de.raindancer.core.platform.util.Scheduling.entity(services.plugin(), viewer, () -> {
                    services.service().renameRoad(road, typed);
                    new RoadEditMenu(services, viewer, road, parent()).open();
                }), () -> { });
    }

    private void reopenPage() {
        new RoadEditMenu(services, viewer, road, parent()).open();
    }
}
