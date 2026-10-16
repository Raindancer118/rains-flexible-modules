package de.raindancer.modules.oneblock.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.platform.util.Times;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.screen.AdminIslandMenu;
import de.raindancer.modules.oneblock.screen.AdminIslandsMenu;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.service.Replies;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /islandadmin} on the island server: the island list, keeping an island up or down for a while or for good,
 * releasing it, its auto-unload, going there, shards, reloading the content and moving islands over from BentoBox.
 */
public final class IslandAdminCommand implements IOneBlockCommand {

    private static final List<String> SUBCOMMANDS = List.of("list", "info", "up", "down", "release", "autounload",
            "tp", "shards", "reload", "migrate");

    private final Supplier<OneBlockServices> services;

    public IslandAdminCommand(Supplier<OneBlockServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        OneBlockServices live = services.get();
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "oneblock.not-allowed");
            return;
        }
        Optional<IslandServer> islands = live.islandServer();
        if (islands.isEmpty()) {
            live.messages().send(sender, "oneblock.admin.not-here");
            return;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (sender instanceof Player player) {
                    new AdminIslandsMenu(live, islands.get(), player, null).open();
                } else {
                    list(live, islands.get(), sender);
                }
            }
            // content.yml is a document in the database: read off the server thread, answered back on it.
            case "reload" -> Scheduling.async(live.plugin(), () -> {
                List<String> problems = islands.get().content().reload();
                Scheduling.global(live.plugin(), () -> {
                    live.messages().send(sender, problems.isEmpty() ? "oneblock.admin.reloaded"
                            : "oneblock.admin.reloaded-with-problems", "problems", String.valueOf(problems.size()));
                    problems.forEach(problem -> sender.sendMessage(" - " + problem));
                });
            });
            case "migrate" -> live.migration().ifPresentOrElse(
                    migration -> migration.run(sender, args.length > 1 && args[1].equalsIgnoreCase("confirm")),
                    () -> live.messages().send(sender, "oneblock.migrate.unavailable"));
            default -> onIsland(live, islands.get(), sender, sub, args);
        }
    }

    private static void onIsland(OneBlockServices live, IslandServer islands, CommandSender sender, String sub,
                                 String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "oneblock.admin.usage");
            return;
        }
        Optional<Island> island = islands.admin().find(args[1], name -> Optional.ofNullable(
                live.server().getOfflinePlayerIfCached(name)).map(OfflinePlayer::getUniqueId));
        if (island.isEmpty()) {
            live.messages().send(sender, "oneblock.admin.no-such-island");
            return;
        }
        UUID id = island.get().id();
        Reply reply = switch (sub) {
            case "info" -> {
                if (sender instanceof Player player) {
                    new AdminIslandMenu(live, islands, player, null, id).open();
                    yield Reply.silent();
                }
                yield Reply.ok("oneblock.admin.info", "player", island.get().ownerName(), "id", island.get().id().toString(),
                        "loaded", String.valueOf(islands.worlds().anyLoaded(island.get())),
                        "auto", String.valueOf(island.get().autoUnload()),
                        "hold", island.get().hold().map(hold -> hold.kind().wire() + (hold.forever() ? " forever"
                                : " until " + hold.until())).orElse("none"));
            }
            case "up", "down" -> {
                Optional<Optional<Duration>> length = length(args.length > 2 ? args[2] : "forever");
                if (length.isEmpty()) {
                    yield Reply.refused("oneblock.admin.bad-time", "time", args[2]);
                }
                yield islands.admin().hold(id, sub.equals("up") ? Hold.Kind.UP : Hold.Kind.DOWN, length.get(),
                        sender.getName());
            }
            case "release" -> islands.admin().release(id);
            case "autounload" -> {
                if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
                    yield Reply.refused("oneblock.admin.usage");
                }
                yield islands.admin().autoUnload(id, args[2].equalsIgnoreCase("on"));
            }
            case "shards" -> {
                try {
                    yield islands.admin().shards(id, Long.parseLong(args.length > 2 ? args[2].replace("+", "") : "x"));
                } catch (NumberFormatException notANumber) {
                    yield Reply.refused("oneblock.admin.usage");
                }
            }
            case "tp" -> {
                if (!(sender instanceof Player player)) {
                    yield Reply.refused("oneblock.only-a-player");
                }
                IslandDimension dimension = args.length > 2 ? IslandDimension.byId(args[2]).orElse(IslandDimension.OVERWORLD)
                        : IslandDimension.OVERWORLD;
                islands.travel().go(player, island.get(), dimension);
                yield Reply.silent();
            }
            default -> Reply.refused("oneblock.admin.usage");
        };
        Replies.say(live.messages(), sender, reply);
    }

    /** Empty when it is not a length of time; a present empty for good. */
    static Optional<Optional<Duration>> length(String typed) {
        if (Times.isPermanent(typed)) {
            return Optional.of(Optional.empty());
        }
        return Times.parseLenient(typed).filter(duration -> !duration.isZero() && !duration.isNegative())
                .map(Optional::of);
    }

    private static void list(OneBlockServices live, IslandServer islands, CommandSender sender) {
        for (Island island : islands.book().all()) {
            sender.sendMessage(island.idPart() + "  " + island.ownerName() + "  " + island.mode().wire()
                    + (islands.worlds().anyLoaded(island) ? "  loaded" : "")
                    + island.hold().map(hold -> "  held " + hold.kind().wire()).orElse("")
                    + (island.autoUnload() ? "" : "  no-auto-unload"));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!source.getSender().hasPermission(PermissionNodes.ADMIN)) {
            return List.of();
        }
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(started)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("migrate")) {
                return List.of("confirm");
            }
            List<String> names = new ArrayList<>();
            services.get().islandServer().ifPresent(islands -> islands.book().all().forEach(island ->
                    names.add(island.ownerName())));
            return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().toList();
        }
        if (args.length == 3) {
            String typed = args[2].toLowerCase(Locale.ROOT);
            List<String> options = switch (sub) {
                case "up", "down" -> List.of("1h", "6h", "1d", "7d", "forever");
                case "autounload" -> List.of("on", "off");
                case "tp" -> List.of("overworld", "nether", "end");
                case "shards" -> List.of("+100", "-100");
                default -> List.of();
            };
            return options.stream().filter(option -> option.startsWith(typed)).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "staff's control over islands: up, down, auto-unload, shards, migration";
    }
}
