package de.raindancer.modules.worldutils.command;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.world.manage.SeedHistory;
import de.raindancer.core.world.manage.WorldFamily;
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.worldutils.WorldUtilsServices;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.screen.ConfirmScreen;
import de.raindancer.modules.worldutils.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /worlds} — create, regen, delete, seeds, info, list.
 *
 * <h2>Confirming</h2>
 * Resetting or deleting a world cannot be undone, so a player gets Core's confirmation dialog first. The
 * console has no inventory to open one in; it adds the word {@code confirm} instead, which is also how a
 * player who knows what they are doing skips the dialog.
 */
public final class WorldsCommand implements IWorldUtilsCommand {

    private static final List<String> SUBCOMMANDS = List.of("list", "create", "regen", "delete", "seeds", "info");
    private static final String FAMILY = "family";
    private static final String CONFIRM = "confirm";

    private final Supplier<WorldUtilsServices> services;

    public WorldsCommand(Supplier<WorldUtilsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldUtilsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            help(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> WorldList.send(live, sender);
            case "create" -> create(live, sender, args);
            case "regen", "regenerate", "reset" -> regenerate(live, sender, args);
            case "delete", "remove" -> delete(live, sender, args);
            case "seeds", "seed" -> seeds(live, sender, args);
            case "info" -> info(live, sender, args);
            default -> help(live, sender);
        }
    }

    /** {@code create <name> [overworld|nether|end|family] [seed]} — in either order after the name. */
    private void create(WorldUtilsServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "worldutils.usage.create");
            return;
        }
        Dimension dimension = null;
        boolean family = false;
        WorldSeed seed = null;
        for (int at = 2; at < args.length; at++) {
            String word = args[at];
            if (word.equalsIgnoreCase(FAMILY)) {
                family = true;
            } else if (dimension == null && Dimension.parse(word).isPresent()) {
                dimension = Dimension.parse(word).get();
            } else if (seed == null) {
                seed = WorldSeed.parse(word).orElse(null);
            }
        }
        live.admin().create(sender, args[1].toLowerCase(Locale.ROOT), dimension, family, seed);
    }

    /** {@code regen <world> [same|random|seed] [family] [confirm]}. */
    private void regenerate(WorldUtilsServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "worldutils.usage.regen");
            return;
        }
        World world = world(live, sender, args[1]);
        if (world == null) {
            return;
        }
        Tail tail = Tail.of(args, 2);
        WorldSeed seed = tail.seed() == null ? WorldSeed.random() : tail.seed();
        Runnable go = () -> live.admin().regenerate(sender, world, seed, tail.family());
        confirmThen(live, sender, tail.confirmed(), go, "worldutils.confirm.regen",
                "world", affected(world, tail.family()), "seed", seed.describe());
    }

    /** {@code delete <world> [family] [confirm]}. */
    private void delete(WorldUtilsServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "worldutils.usage.delete");
            return;
        }
        World world = world(live, sender, args[1]);
        if (world == null) {
            return;
        }
        Tail tail = Tail.of(args, 2);
        confirmThen(live, sender, tail.confirmed(), () -> live.admin().delete(sender, world, tail.family()),
                "worldutils.confirm.delete", "world", affected(world, tail.family()), "seed", "");
    }

    private void seeds(WorldUtilsServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "worldutils.usage.seeds");
            return;
        }
        // Not required to be loaded: the history of a deleted world is exactly what somebody looks for.
        String name = args[1];
        List<SeedHistory.Entry> seeds = live.admin().seeds(name);
        if (seeds.isEmpty()) {
            live.messages().send(sender, "worldutils.seeds.none", "world", name);
            return;
        }
        live.messages().send(sender, "worldutils.seeds.heading", "world", name, "count", seeds.size());
        for (SeedHistory.Entry entry : seeds) {
            live.messages().sendPlain(sender, "worldutils.seeds.row",
                    "world", name,
                    "seed", entry.seed(),
                    "when", Times.ago(entry.at()),
                    "cause", entry.cause().name().toLowerCase(Locale.ROOT));
        }
    }

    private void info(WorldUtilsServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "worldutils.usage.info");
            return;
        }
        World world = world(live, sender, args[1]);
        if (world == null) {
            return;
        }
        live.messages().send(sender, "worldutils.info",
                "world", world.getName(),
                "dimension", Dimension.of(world.getEnvironment()).map(Dimension::label).orElse("custom"),
                "seed", world.getSeed(),
                "players", world.getPlayers().size(),
                "family", String.join(", ", WorldFamily.of(world.getName()).members().stream()
                        .filter(member -> live.server().getWorld(member) != null).toList()),
                "made", live.admin().isManaged(world.getName()) ? "made with /worlds" : "not made here");
    }

    private World world(WorldUtilsServices live, CommandSender sender, String name) {
        World world = live.server().getWorld(name);
        if (world == null) {
            live.messages().send(sender, "worldutils.unknown-world", "world", name);
        }
        return world;
    }

    private static String affected(World world, boolean family) {
        return family ? String.join(", ", WorldFamily.of(world.getName()).members()) : world.getName();
    }

    private void confirmThen(WorldUtilsServices live, CommandSender sender, boolean confirmed, Runnable action,
                             String question, Object... values) {
        if (confirmed) {
            action.run();
            return;
        }
        if (sender instanceof Player player) {
            new ConfirmScreen(live, player, live.messages().raw(question + "-title"),
                    List.of(live.messages().raw(question + "-detail")
                            .replace("<world>", String.valueOf(values[1]))
                            .replace("<seed>", String.valueOf(values[3]))),
                    action).open();
            return;
        }
        live.messages().send(sender, "worldutils.confirm.console", values);
    }

    private void help(WorldUtilsServices live, CommandSender sender) {
        live.messages().lines("worldutils.help").forEach(sender::sendMessage);
    }

    /** The optional words after a world's name, in any order. */
    private record Tail(WorldSeed seed, boolean family, boolean confirmed) {

        static Tail of(String[] args, int from) {
            WorldSeed seed = null;
            boolean family = false;
            boolean confirmed = false;
            for (int at = from; at < args.length; at++) {
                String word = args[at];
                if (word.equalsIgnoreCase(FAMILY)) {
                    family = true;
                } else if (word.equalsIgnoreCase(CONFIRM)) {
                    confirmed = true;
                } else if (seed == null) {
                    seed = WorldSeed.parse(word).orElse(null);
                }
            }
            return new Tail(seed, family, confirmed);
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldUtilsServices live = services.get();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return startingWith(SUBCOMMANDS, typed);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("create")) {
                return List.of();
            }
            List<String> names = new ArrayList<>(live.server().getWorlds().stream().map(World::getName).toList());
            return startingWith(names, typed);
        }
        return switch (sub) {
            case "create" -> startingWith(List.of("overworld", "nether", "end", FAMILY, "random"), typed);
            case "regen", "regenerate", "reset" -> startingWith(List.of("same", "random", FAMILY, CONFIRM), typed);
            case "delete", "remove" -> startingWith(List.of(FAMILY, CONFIRM), typed);
            default -> List.of();
        };
    }

    private static List<String> startingWith(List<String> options, String typed) {
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.ADMIN;
    }

    @Override
    public String describe() {
        return "creating, resetting and deleting worlds, and their seed history";
    }
}
