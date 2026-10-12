package de.raindancer.modules.arena.command;

import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Layout;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import de.raindancer.modules.arena.util.PermissionNodes;
import de.raindancer.modules.arena.util.Places;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /arena} on the arena server: {@code hub}, {@code leave}, and the admin commands that set it up.
 *
 * <p>Every admin command that needs a place takes it from where the admin stands, or — from the console — as
 * coordinates, so the arena can be set up without anybody standing in it.
 */
public final class ArenaCommand implements IArenaCommand {

    private static final List<String> PLAYER = List.of("hub", "leave");
    private static final List<String> ADMIN = List.of("build", "sethub", "pit", "info");
    private static final List<String> PIT_ACTIONS = List.of("floor", "spawn", "clearspawns", "remove");

    private final Supplier<ArenaServices> services;

    public ArenaCommand(Supplier<ArenaServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ArenaServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("admin")) {
            if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                live.messages().send(sender, "arena.not-allowed");
                return;
            }
            admin(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "arena.only-a-player");
            return;
        }
        switch (first) {
            case "leave" -> live.arena().leave(player);
            case "", "hub" -> live.arena().toHub(player);
            default -> live.messages().send(player, "arena.usage");
        }
    }

    private void admin(ArenaServices live, CommandSender sender, String[] args) {
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (what) {
            case "build" -> {
                if (args.length < 3) {
                    live.messages().send(sender, "arena.admin.usage");
                    return;
                }
                live.arena().build(sender, args[1], args[2]);
            }
            case "sethub" -> place(live, sender, args, 1).ifPresent(hub -> live.arena().setHub(sender, hub));
            case "pit" -> pit(live, sender, args);
            case "info" -> info(live, sender);
            default -> live.messages().send(sender, "arena.admin.usage");
        }
    }

    private void pit(ArenaServices live, CommandSender sender, String[] args) {
        if (args.length < 3) {
            live.messages().send(sender, "arena.admin.usage");
            return;
        }
        String name = args[1];
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "floor" -> box(live, sender, args, 3).ifPresent(floor -> live.arena().pitFloor(sender, name, floor));
            case "spawn" -> place(live, sender, args, 3).ifPresent(spawn -> live.arena().pitSpawn(sender, name, spawn));
            case "clearspawns" -> live.arena().pitClearSpawns(sender, name);
            case "remove" -> live.arena().pitRemove(sender, name);
            default -> live.messages().send(sender, "arena.admin.usage");
        }
    }

    /** {@code [world x y z [yaw pitch]]} from {@code at}, or where the sender stands. */
    private static Optional<Place> place(ArenaServices live, CommandSender sender, String[] args, int at) {
        if (args.length > at) {
            try {
                return Optional.of(new Place(args[at], Double.parseDouble(args[at + 1]),
                        Double.parseDouble(args[at + 2]), Double.parseDouble(args[at + 3]),
                        args.length > at + 4 ? Float.parseFloat(args[at + 4]) : 0f,
                        args.length > at + 5 ? Float.parseFloat(args[at + 5]) : 0f));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException wrong) {
                live.messages().send(sender, "arena.admin.bad-place");
                return Optional.empty();
            }
        }
        if (sender instanceof Player player) {
            return Optional.of(Places.of(player.getLocation()));
        }
        live.messages().send(sender, "arena.admin.bad-place");
        return Optional.empty();
    }

    /** {@code world x1 y1 z1 x2 y2 z2} — a floor is always given in full, it cannot be stood in at once. */
    private static Optional<Box> box(ArenaServices live, CommandSender sender, String[] args, int at) {
        try {
            return Optional.of(Box.of(args[at], Integer.parseInt(args[at + 1]), Integer.parseInt(args[at + 2]),
                    Integer.parseInt(args[at + 3]), Integer.parseInt(args[at + 4]), Integer.parseInt(args[at + 5]),
                    Integer.parseInt(args[at + 6])));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException wrong) {
            live.messages().send(sender, "arena.admin.bad-box");
            return Optional.empty();
        }
    }

    private static void info(ArenaServices live, CommandSender sender) {
        Layout layout = live.arena().layout();
        live.messages().send(sender, "arena.admin.info.head", "worlds", layout.worlds().size(),
                "pits", layout.pits().size(), "hub", layout.hub().map(Place::world).orElse("—"));
        for (ArenaWorld world : layout.worlds()) {
            Box box = world.mapBox();
            live.messages().send(sender, "arena.admin.info.world", "world", world.name(), "map", world.map(),
                    "box", box.minX() + " " + box.minY() + " " + box.minZ() + " → " + box.maxX() + " " + box.maxY()
                            + " " + box.maxZ());
        }
        for (Pit pit : layout.pits()) {
            live.messages().send(sender, "arena.admin.info.pit", "pit", pit.id(), "world", pit.world(),
                    "spawns", pit.spawns().size(), "ready", pit.isReady() ? "ready" : "not ready");
        }
        live.messages().send(sender, "arena.admin.info.maps", "maps", String.join(", ", live.arena().mapFiles()));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = admin ? List.of("hub", "leave", "admin") : PLAYER;
            return options.stream().filter(option -> option.startsWith(started)).toList();
        }
        if (!admin || !args[0].equalsIgnoreCase("admin")) {
            return List.of();
        }
        ArenaServices live = services.get();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return ADMIN.stream().filter(option -> option.startsWith(last)).toList();
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        if (what.equals("build") && args.length == 4) {
            return live.arena().mapFiles().stream().filter(file -> file.toLowerCase(Locale.ROOT).startsWith(last))
                    .toList();
        }
        if (what.equals("pit") && args.length == 3) {
            return live.arena().layout().pits().stream().map(Pit::id).filter(id -> id.startsWith(last)).toList();
        }
        if (what.equals("pit") && args.length == 4) {
            return PIT_ACTIONS.stream().filter(option -> option.startsWith(last)).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "the hub, leaving, and setting the arena up";
    }
}
