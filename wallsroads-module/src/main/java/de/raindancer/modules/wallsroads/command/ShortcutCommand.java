package de.raindancer.modules.wallsroads.command;

import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.screen.WallsRoadsListMenu;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /walls} and {@code /roads} — the two lists, one word each.
 *
 * <h2>Why these are commands rather than aliases of {@code /wallsroads}</h2>
 * An alias would open the same thing the main command does, which makes {@code /walls} a second name
 * for the front page rather than a way to the walls. Paper hands a {@link
 * io.papermc.paper.command.brigadier.BasicCommand} its arguments and not the word that was typed, so
 * a single handler cannot tell which alias it was reached by — the only way for the two words to mean
 * two different things is for them to be two commands.
 *
 * <p>Given a name, it opens that one directly: {@code /wall eastgate} is faster than the front page
 * and a list for somebody who already knows what they are looking for.
 */
public final class ShortcutCommand implements IWallsRoadsCommand {

    private final Supplier<WallsRoadsServices> services;
    private final WallsRoadsListMenu.Filter filter;

    public ShortcutCommand(Supplier<WallsRoadsServices> services, WallsRoadsListMenu.Filter filter) {
        this.services = services;
        this.filter = filter;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WallsRoadsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "wallsroads.only-a-player");
            return;
        }
        if (args.length == 0) {
            new WallsRoadsListMenu(live, player, null, filter, player.getUniqueId()).open();
            return;
        }

        String wanted = args[0];
        if (filter == WallsRoadsListMenu.Filter.WALLS) {
            live.registry().allWalls().stream()
                    .filter(wall -> matches(wall.id(), wall.name(), wanted))
                    .findFirst()
                    .ifPresentOrElse(wall -> live.screens().wall(player, wall),
                            () -> live.messages().send(player, "wallsroads.unknown-wall", "name", wanted));
            return;
        }
        live.registry().allRoads().stream()
                .filter(road -> matches(road.id(), road.name(), wanted))
                .findFirst()
                .ifPresentOrElse(road -> live.screens().road(player, road),
                        () -> live.messages().send(player, "wallsroads.unknown-road", "name", wanted));
    }

    private static boolean matches(String id, String name, String wanted) {
        return id.equals(wanted) || name.equalsIgnoreCase(wanted);
    }

    /** Only what this player owns, unless they may manage anything — a list of names is a map of the server. */
    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length > 1) {
            return List.of();
        }
        WallsRoadsServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            return List.of();
        }
        boolean staff = player.hasPermission(PermissionNodes.MANAGE_ANY);
        String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";

        List<String> names = new ArrayList<>();
        if (filter == WallsRoadsListMenu.Filter.WALLS) {
            for (Wall wall : staff ? live.registry().allWalls()
                    : live.registry().wallsOwnedBy(player.getUniqueId())) {
                names.add(wall.name());
            }
        } else {
            for (RoadPath road : staff ? live.registry().allRoads()
                    : live.registry().roadsOwnedBy(player.getUniqueId())) {
                names.add(road.name());
            }
        }
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return filter == WallsRoadsListMenu.Filter.WALLS
                ? "open your walls, or one by name" : "open your roads, or one by name";
    }
}
