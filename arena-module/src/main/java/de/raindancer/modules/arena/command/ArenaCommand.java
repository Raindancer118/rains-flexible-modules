package de.raindancer.modules.arena.command;

import de.raindancer.modules.arena.api.SpecArgs;
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

    private static final List<String> PLAYER = List.of("hub", "leave", "queue", "wave", "spectate", "stats", "menu");
    private static final List<String> ADMIN = List.of("build", "sethub", "pit", "info", "kit", "npc", "hold", "return");
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
            case "", "menu" -> live.screens().main(player);
            case "leave" -> {
                live.matches().left(player.getUniqueId());
                live.queue().leave(player.getUniqueId());
                live.arena().leave(player);
            }
            case "hub" -> {
                if (live.matches().inFight(player.getUniqueId())) {
                    live.matches().left(player.getUniqueId());
                }
                live.matches().stopWatching(player.getUniqueId());
                de.raindancer.modules.arena.service.HubState.apply(player, live.config());
                live.arena().toHub(player);
            }
            case "queue" -> live.queue().join(player, SpecArgs.parse(args, 1, de.raindancer.modules.arena.api.Mode.DUEL));
            case "wave", "waves" -> {
                de.raindancer.modules.arena.api.MatchSpec spec = SpecArgs.parse(args, 1,
                        de.raindancer.modules.arena.api.Mode.WAVES);
                if (spec.mode() != de.raindancer.modules.arena.api.Mode.WAVES) {
                    spec = de.raindancer.modules.arena.api.MatchSpec.waves(null, spec.kit());
                }
                live.queue().join(player, spec);
            }
            case "spectate" -> {
                Player target = args.length > 1 ? live.server().getPlayerExact(args[1]) : null;
                if (target == null || !live.matches().spectate(player, target.getUniqueId())) {
                    live.messages().send(player, "arena.spectate.nothing");
                } else {
                    live.messages().send(player, "arena.spectate.watching");
                }
            }
            case "stats" -> stats(live, player, args.length > 1 ? args[1] : player.getName());
            default -> live.messages().send(player, "arena.usage");
        }
    }

    private static void stats(ArenaServices live, Player player, String name) {
        org.bukkit.OfflinePlayer whose = live.server().getOfflinePlayerIfCached(name);
        java.util.UUID id = whose == null ? null : whose.getUniqueId();
        if (id == null) {
            live.messages().send(player, "arena.stats.none", "player", name);
            return;
        }
        de.raindancer.core.platform.util.Scheduling.async(live.plugin(), () -> tell(live, player, name, live.stats().of(id)));
    }

    private static void tell(ArenaServices live, Player player, String name,
                             java.util.Optional<de.raindancer.modules.arena.model.Stats> stats) {
        if (stats.isEmpty()) {
            live.messages().send(player, "arena.stats.none", "player", name);
            return;
        }
        de.raindancer.modules.arena.model.Stats found = stats.get();
        live.messages().send(player, "arena.stats.line", "player", found.name(), "wins", found.wins(),
                "losses", found.losses(), "draws", found.draws(), "kills", found.kills(), "deaths", found.deaths(),
                "wave", found.bestWave());
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
            case "hold" -> {
                // Everybody named (or everybody here not fighting) waits for survival to come back from an update.
                int count = 0;
                for (Player player : targets(live, args)) {
                    live.arena().hold(player);
                    count++;
                }
                live.messages().send(sender, "arena.admin.held", "count", count);
            }
            case "return" -> live.messages().send(sender, "arena.admin.returned", "count", live.arena().returnHeld());
            case "kit" -> kit(live, sender, args);
            case "npc" -> npc(live, sender, args);
            default -> live.messages().send(sender, "arena.admin.usage");
        }
    }

    /** The players named after the subcommand, or — with {@code all} or none named — everybody here not fighting. */
    private static List<Player> targets(ArenaServices live, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            return live.server().getOnlinePlayers().stream()
                    .filter(player -> !live.matches().inFight(player.getUniqueId())).map(player -> (Player) player).toList();
        }
        List<Player> named = new java.util.ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            Player player = live.server().getPlayerExact(args[index]);
            if (player != null) {
                named.add(player);
            }
        }
        return named;
    }

    /** {@code kit save <id> [name…]} from what the admin holds, {@code kit remove <id>}. */
    private void kit(ArenaServices live, CommandSender sender, String[] args) {
        if (args.length < 3) {
            live.messages().send(sender, "arena.admin.usage");
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "arena.only-a-player");
                    return;
                }
                if (id.equals(de.raindancer.modules.arena.api.MatchSpec.OWN_GEAR)) {
                    live.messages().send(sender, "arena.admin.kit-reserved");
                    return;
                }
                String name = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : id;
                org.bukkit.Material held = player.getInventory().getItemInMainHand().getType();
                boolean saved = live.kits().saveFrom(player, id, name, held.isAir() ? "IRON_SWORD" : held.name());
                live.messages().send(sender, saved ? "arena.admin.kit-saved" : "arena.admin.not-saved", "kit", id);
            }
            case "remove" -> live.messages().send(sender, live.kits().remove(id) ? "arena.admin.kit-removed"
                    : "arena.invite.refuse.no-kit", "kit", id);
            default -> live.messages().send(sender, "arena.admin.usage");
        }
    }

    /** {@code npc place <role>} where the admin stands, {@code npc remove} the nearest. */
    private void npc(ArenaServices live, CommandSender sender, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (what.equals("place") && args.length > 3) {
            // From anywhere, the console too: npc place <role> world x y z [yaw pitch]
            java.util.Optional<de.raindancer.modules.arena.model.NpcRole> role =
                    de.raindancer.modules.arena.model.NpcRole.parse(args[2]);
            java.util.Optional<de.raindancer.modules.arena.model.Place> at = place(live, sender, args, 3);
            if (role.isEmpty() || at.isEmpty()) {
                live.messages().send(sender, "arena.admin.npc-usage");
                return;
            }
            Places.location(live.server(), at.get()).ifPresentOrElse(location ->
                    de.raindancer.core.platform.util.Scheduling.region(live.plugin(), location, () -> {
                        live.npcs().place(location, role.get());
                        live.messages().send(sender, "arena.admin.npc-placed", "role", role.get().title());
                    }), () -> live.messages().send(sender, "arena.admin.not-an-arena-world", "world", at.get().world()));
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "arena.only-a-player");
            return;
        }
        if (what.equals("remove")) {
            live.npcs().removeNearest(player).ifPresentOrElse(
                    role -> live.messages().send(player, "arena.admin.npc-removed", "role", role.title()),
                    () -> live.messages().send(player, "arena.admin.npc-none"));
            return;
        }
        java.util.Optional<de.raindancer.modules.arena.model.NpcRole> role = args.length > 2
                ? de.raindancer.modules.arena.model.NpcRole.parse(args[2]) : java.util.Optional.empty();
        if (!what.equals("place") || role.isEmpty()) {
            live.messages().send(player, "arena.admin.npc-usage");
            return;
        }
        live.npcs().place(player, role.get());
        live.messages().send(player, "arena.admin.npc-placed", "role", role.get().title());
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
            List<String> options = admin ? java.util.stream.Stream.concat(PLAYER.stream(), java.util.stream.Stream.of("admin")).toList() : PLAYER;
            return options.stream().filter(option -> option.startsWith(started)).toList();
        }
        if (args[0].equalsIgnoreCase("wave") || args[0].equalsIgnoreCase("waves")) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.concat(de.raindancer.modules.arena.api.WaveSpec.PRESETS.keySet().stream(),
                    services.get().kits().all().stream().map(de.raindancer.modules.arena.model.Kit::id))
                    .filter(option -> option.startsWith(last)).sorted().toList();
        }
        if (args[0].equalsIgnoreCase("queue")) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.concat(java.util.stream.Stream.of("duel", "sumo", "2v2", "3v3", "ffa", "own", "bo3"),
                    services.get().kits().all().stream().map(de.raindancer.modules.arena.model.Kit::id))
                    .filter(option -> option.startsWith(last)).toList();
        }
        if (args[0].equalsIgnoreCase("spectate") && args.length == 2) {
            return services.get().server().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
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
        if (what.equals("kit") && args.length == 3) {
            return java.util.stream.Stream.of("save", "remove").filter(option -> option.startsWith(last)).toList();
        }
        if (what.equals("npc") && args.length == 3) {
            return java.util.stream.Stream.of("place", "remove").filter(option -> option.startsWith(last)).toList();
        }
        if (what.equals("npc") && args.length == 4) {
            return java.util.Arrays.stream(de.raindancer.modules.arena.model.NpcRole.values())
                    .map(role -> role.name().toLowerCase(Locale.ROOT)).filter(option -> option.startsWith(last)).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "the arena menu, the hub, the queue, waves, watching, stats, and setting the arena up";
    }
}
