package de.raindancer.modules.ranks.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.modules.ranks.RanksServices;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.screen.RankMenu;
import de.raindancer.modules.ranks.service.RankService.Outcome;
import de.raindancer.modules.ranks.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /rank} opens your ranks; {@code info [player]}, {@code wear <rank|none|auto>},
 * {@code show <place> [on|off]}. Staff: {@code give|take <player> <rank>}, {@code list [rank]}, {@code reload}.
 */
public final class RankCommand implements IRanksCommand {

    private final Supplier<RanksServices> services;

    public RankCommand(Supplier<RanksServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        RanksServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "" -> asPlayer(live, sender).ifPresent(player -> new RankMenu(live, player, null).open());
            case "info" -> info(live, sender, args.length > 1 ? args[1] : null);
            case "wear" -> asPlayer(live, sender).ifPresent(player -> wear(live, player, args));
            case "show" -> asPlayer(live, sender).ifPresent(player -> show(live, player, args));
            case "give", "take" -> staffChange(live, sender, args, first.equals("give"));
            case "list" -> list(live, sender, args);
            case "create", "edit", "delete" -> manage(live, sender, first, args);
            case "reload" -> {
                if (!sender.hasPermission(PermissionNodes.ADMIN)) {
                    live.messages().send(sender, "ranks.usage");
                    return;
                }
                live.messages().send(sender, "ranks.reloaded", "count", String.valueOf(live.ranks().reload()));
                live.ranks().problems().forEach(problem -> live.messages().send(sender, "ranks.problem", "problem", problem));
            }
            default -> live.messages().send(sender, "ranks.usage");
        }
    }

    private static void wear(RanksServices live, Player player, String[] args) {
        if (args.length < 2) {
            live.messages().send(player, "ranks.usage-wear");
            return;
        }
        String wanted = args[1].toLowerCase(Locale.ROOT);
        String id = wanted.equals("auto") ? "" : wanted;
        Optional<Rank> rank = live.ranks().rank(id);
        if (!id.isEmpty() && !id.equals(Holder.HIDDEN) && rank.isEmpty()) {
            live.messages().send(player, "ranks.unknown", "name", args[1]);
            return;
        }
        Outcome outcome = live.ranks().wear(player.getUniqueId(), id);
        switch (outcome) {
            case DONE, ALREADY -> {
                if (id.equals(Holder.HIDDEN)) {
                    live.messages().send(player, "ranks.wear-none");
                } else {
                    live.ranks().worn(player.getUniqueId()).ifPresentOrElse(
                            worn -> live.messages().send(player, "ranks.wear", "rank", new Markup(worn.tag())),
                            () -> live.messages().send(player, "ranks.none-yours"));
                }
            }
            case NOT_HELD -> live.messages().send(player, "ranks.not-yours", "rank", new Markup(rank.orElseThrow().tag()));
            default -> tellRefusal(live, player, outcome);
        }
    }

    private static void show(RanksServices live, Player player, String[] args) {
        Optional<Place> place = args.length < 2 ? Optional.empty() : Place.byWord(args[1]);
        if (place.isEmpty()) {
            live.messages().send(player, "ranks.usage-show");
            return;
        }
        boolean now = live.ranks().holder(player.getUniqueId()).shows(place.get());
        boolean on;
        if (args.length < 3) {
            on = !now;
        } else if (List.of("on", "yes", "true").contains(args[2].toLowerCase(Locale.ROOT))) {
            on = true;
        } else if (List.of("off", "no", "false").contains(args[2].toLowerCase(Locale.ROOT))) {
            on = false;
        } else {
            live.messages().send(player, "ranks.usage-show");
            return;
        }
        Outcome outcome = live.ranks().show(player.getUniqueId(), place.get(), on);
        if (outcome == Outcome.DONE || outcome == Outcome.ALREADY) {
            live.messages().send(player, on ? "ranks.shown" : "ranks.hidden", "place", place.get().title());
        } else {
            tellRefusal(live, player, outcome);
        }
    }

    private static void staffChange(RanksServices live, CommandSender sender, String[] args, boolean give) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "ranks.usage");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "ranks.usage-staff");
            return;
        }
        Optional<Rank> rank = live.ranks().rank(args[2]);
        if (rank.isEmpty()) {
            live.messages().send(sender, "ranks.unknown", "name", args[2]);
            return;
        }
        Optional<OfflinePlayer> target = PlayerTargets.find(live.server(), sender, args[1]);
        if (target.isEmpty()) {
            live.messages().send(sender, "ranks.never-here", "player", args[1]);
            return;
        }
        UUID id = target.get().getUniqueId();
        String name = PlayerTargets.shownName(target.get());
        Markup tag = new Markup(rank.get().tag());
        Outcome outcome = give ? live.ranks().give(id, rank.get()) : live.ranks().take(id, rank.get());
        switch (outcome) {
            case DONE -> {
                live.messages().send(sender, give ? "ranks.given" : "ranks.taken", "player", name, "rank", tag);
                Player online = target.get().getPlayer();
                if (online != null && !online.equals(sender)) {
                    live.messages().send(online, give ? "ranks.given-you" : "ranks.taken-you", "rank", tag);
                }
            }
            case ALREADY -> live.messages().send(sender, "ranks.already-holds", "player", name, "rank", tag);
            case NOT_HELD -> live.messages().send(sender, "ranks.does-not-hold", "player", name, "rank", tag);
            default -> tellRefusal(live, sender, outcome);
        }
    }

    /** {@code create <id> <tag…>}, {@code edit <id> tag|title|weight|style|team …}, {@code delete <id> confirm}. */
    private static void manage(RanksServices live, CommandSender sender, String what, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "ranks.usage");
            return;
        }
        String rest = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
        Outcome outcome;
        switch (what) {
            case "create" -> {
                if (args.length < 3) {
                    live.messages().send(sender, "ranks.usage-manage");
                    return;
                }
                outcome = live.ranks().create(args[1].toLowerCase(Locale.ROOT),
                        String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)), 50);
            }
            case "delete" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "ranks.usage-manage");
                    return;
                }
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    live.messages().send(sender, "ranks.delete-confirm", "id", args[1].toLowerCase(Locale.ROOT));
                    return;
                }
                outcome = live.ranks().delete(args[1].toLowerCase(Locale.ROOT));
            }
            default -> {
                if (args.length < 4) {
                    live.messages().send(sender, "ranks.usage-manage");
                    return;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                outcome = switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "tag" -> live.ranks().retag(id, rest);
                    case "title" -> live.ranks().rename(id, rest);
                    case "weight" -> {
                        try {
                            yield live.ranks().reweigh(id, Integer.parseInt(args[3]));
                        } catch (NumberFormatException notANumber) {
                            yield Outcome.INVALID;
                        }
                    }
                    case "team" -> live.ranks().team(id, List.of("on", "yes", "true").contains(args[3].toLowerCase(Locale.ROOT)));
                    case "style" -> {
                        if (args[3].equalsIgnoreCase("reset")) {
                            yield live.ranks().restyle(id, de.raindancer.core.ui.text.NameStyle.NONE);
                        }
                        var read = de.raindancer.core.ui.text.TypedStyles.read(
                                List.of(java.util.Arrays.copyOfRange(args, 3, args.length)), word -> null);
                        if (read.style().isEmpty()) {
                            live.messages().send(sender, "ranks.style-unreadable", "problem", read.problem());
                            yield null;
                        }
                        yield live.ranks().restyle(id, read.style().get());
                    }
                    default -> {
                        live.messages().send(sender, "ranks.usage-manage");
                        yield null;
                    }
                };
                if (outcome == null) {
                    return;
                }
            }
        }
        switch (outcome) {
            case DONE -> live.messages().send(sender, "ranks.managed");
            case ALREADY -> live.messages().send(sender, "ranks.exists", "id", args[1]);
            case NOT_HELD -> live.messages().send(sender, "ranks.unknown", "name", args[1]);
            case INVALID -> live.messages().send(sender, "ranks.invalid");
            default -> tellRefusal(live, sender, outcome);
        }
    }

    private static void list(RanksServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "ranks.usage");
            return;
        }
        List<Rank> which;
        if (args.length > 1) {
            Optional<Rank> one = live.ranks().rank(args[1]);
            if (one.isEmpty()) {
                live.messages().send(sender, "ranks.unknown", "name", args[1]);
                return;
            }
            which = List.of(one.get());
        } else {
            which = live.ranks().ranks();
        }
        if (which.isEmpty()) {
            live.messages().send(sender, "ranks.no-ranks");
            return;
        }
        for (Rank rank : which) {
            List<String> names = live.ranks().holdersOf(rank).stream()
                    .map(id -> live.core().identities().nameOf(id).orElse(id.toString().substring(0, 8)))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            live.messages().send(sender, "ranks.list-line", "rank", new Markup(rank.tag()), "id", rank.id(),
                    "count", String.valueOf(names.size()), "players", names.isEmpty() ? "—" : String.join(", ", names));
        }
    }

    private static void info(RanksServices live, CommandSender sender, String who) {
        OfflinePlayer target;
        if (who == null) {
            Optional<Player> self = asPlayer(live, sender);
            if (self.isEmpty()) {
                return;
            }
            target = self.get();
        } else {
            Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), sender, who);
            if (found.isEmpty()) {
                live.messages().send(sender, "ranks.never-here", "player", who);
                return;
            }
            target = found.get();
        }
        String name = PlayerTargets.shownName(target);
        List<Rank> held = live.ranks().held(target.getUniqueId());
        if (held.isEmpty()) {
            live.messages().send(sender, "ranks.info-none", "player", name);
            return;
        }
        live.messages().send(sender, "ranks.info", "player", name);
        Optional<Rank> worn = live.ranks().worn(target.getUniqueId());
        for (Rank rank : held) {
            live.messages().send(sender, worn.filter(rank::equals).isPresent() ? "ranks.info-worn" : "ranks.info-line",
                    "rank", new Markup(rank.tag()), "title", rank.title());
        }
    }

    private static void tellRefusal(RanksServices live, CommandSender sender, Outcome outcome) {
        live.messages().send(sender, switch (outcome) {
            case NOT_ALLOWED -> "ranks.choosing-off";
            case NOT_SAVED -> "ranks.not-saved";
            default -> "ranks.usage";
        });
    }

    private static Optional<Player> asPlayer(RanksServices live, CommandSender sender) {
        if (sender instanceof Player player) {
            return Optional.of(player);
        }
        live.messages().send(sender, "ranks.only-a-player");
        return Optional.empty();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        RanksServices live = services.get();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("info", "wear", "show"));
            if (admin) {
                options.addAll(List.of("give", "take", "list", "reload", "create", "edit", "delete"));
            }
        } else if (args.length == 2 && first.equals("wear") && sender instanceof Player player) {
            live.ranks().held(player.getUniqueId()).forEach(rank -> options.add(rank.id()));
            options.addAll(List.of("none", "auto"));
        } else if (args.length == 2 && first.equals("show")) {
            for (Place place : Place.values()) {
                options.add(place.word());
            }
        } else if (args.length == 3 && first.equals("show")) {
            options.addAll(List.of("on", "off"));
        } else if (args.length == 2 && (first.equals("info") || (admin && List.of("give", "take").contains(first)))) {
            return PlayerTargets.suggest(live.server(), sender, args[1], viewer -> true);
        } else if (admin && args.length == 3 && first.equals("edit")) {
            options.addAll(List.of("tag", "title", "weight", "style", "team"));
        } else if (admin && args.length == 4 && first.equals("edit") && args[2].equalsIgnoreCase("team")) {
            options.addAll(List.of("on", "off"));
        } else if (admin && args.length == 3 && first.equals("delete")) {
            options.add("confirm");
        } else if (admin && ((args.length == 3 && List.of("give", "take").contains(first))
                || (args.length == 2 && List.of("list", "edit", "delete").contains(first)))) {
            live.ranks().ranks().forEach(rank -> options.add(rank.id()));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
