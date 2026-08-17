package de.raindancer.modules.wallsroads.command;

import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.rules.CreateRule;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /wallsroads wall|road new|remove|list}, {@code cancel} — everything typing beats clicking,
 * or that a menu cannot ask for (starting a selection needs the in-world stick regardless). Every
 * click-equivalent action lives on {@link de.raindancer.modules.wallsroads.screen.WallEditMenu}/
 * {@link de.raindancer.modules.wallsroads.screen.RoadEditMenu} instead.
 */
public final class WallsRoadsCommand implements IWallsRoadsCommand {

    private static final List<String> SUBCOMMANDS = List.of("wall", "road", "cancel", "list");

    private final Supplier<WallsRoadsServices> services;
    private final CreateRule createRule = new CreateRule();

    public WallsRoadsCommand(Supplier<WallsRoadsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WallsRoadsServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            if (sender instanceof Player player) {
                live.screens().list(player);
                return;
            }
            live.messages().send(sender, "wallsroads.usage");
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wall" -> wallSub(live, sender, args);
            case "road" -> roadSub(live, sender, args);
            case "cancel" -> cancel(live, sender);
            case "list" -> {
                if (sender instanceof Player player) {
                    live.screens().list(player);
                } else {
                    live.messages().send(sender, "wallsroads.only-a-player");
                }
            }
            default -> live.messages().send(sender, "wallsroads.usage");
        }
    }

    private void wallSub(WallsRoadsServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "wallsroads.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "wallsroads.usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("new")) {
            WallsRoadsSettings settings = live.config();
            if (!createRule.mayCreate(settings.openCreation(), player)) {
                live.messages().send(player, "wallsroads.create.no-permission");
                return;
            }
            live.selectionFlow().begin(player, WallsRoadsSelectionFlow.Purpose.WALL);
            return;
        }
        Optional<Wall> wall = live.registry().allWalls().stream()
                .filter(w -> w.id().equals(args[1]) || w.name().equalsIgnoreCase(args[1]))
                .findFirst();
        if (wall.isEmpty()) {
            live.messages().send(player, "wallsroads.unknown-wall", "name", args[1]);
            return;
        }
        live.screens().wall(player, wall.get());
    }

    private void roadSub(WallsRoadsServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "wallsroads.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "wallsroads.usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("new")) {
            WallsRoadsSettings settings = live.config();
            if (!createRule.mayCreate(settings.openCreation(), player)) {
                live.messages().send(player, "wallsroads.create.no-permission");
                return;
            }
            live.selectionFlow().begin(player, WallsRoadsSelectionFlow.Purpose.ROAD);
            return;
        }
        Optional<RoadPath> road = live.registry().allRoads().stream()
                .filter(r -> r.id().equals(args[1]) || r.name().equalsIgnoreCase(args[1]))
                .findFirst();
        if (road.isEmpty()) {
            live.messages().send(player, "wallsroads.unknown-road", "name", args[1]);
            return;
        }
        live.screens().road(player, road.get());
    }

    private void cancel(WallsRoadsServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "wallsroads.only-a-player");
            return;
        }
        live.selectionFlow().cancel(player);
    }

    @Override
    public @NotNull java.util.Collection<String> suggest(@NotNull CommandSourceStack source,
                                                          String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(typed)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("wall") || args[0].equalsIgnoreCase("road"))) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return List.of("new").stream().filter(name -> name.startsWith(typed)).toList();
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "mark out, build, edit and remove town walls and roads";
    }
}
