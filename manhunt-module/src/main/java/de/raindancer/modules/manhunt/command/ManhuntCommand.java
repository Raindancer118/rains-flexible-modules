package de.raindancer.modules.manhunt.command;

import de.raindancer.core.social.team.Teams;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /manhunt} — the sides, and nothing else.
 *
 * <h2>Why there is no start, stop or reset here any more</h2>
 * A hunt is started by the speedrun lobby's own green block and ended by its own goal or by
 * {@code /speedrunreset}, because a hunt <em>is</em> a run in that lobby. Two commands that both
 * claimed to start the same thing is precisely what made the old module confusing to run: one of them
 * worked, and which one depended on where you were standing.
 */
public final class ManhuntCommand implements IManhuntCommand {

    private static final String RUNNER = "runner";
    private static final String HUNTER = "hunter";

    private final Supplier<ManhuntServices> services;

    public ManhuntCommand(Supplier<ManhuntServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ManhuntServices live = services.get();
        CommandSender sender = source.getSender();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (word) {
            case "" -> open(live, sender);
            case "join" -> join(live, sender, args);
            case "leave" -> leave(live, sender);
            case "assign" -> assign(live, sender, args);
            case "status" -> status(live, sender);
            default -> live.messages().send(sender, "manhunt.unknown-word", "word", word);
        }
    }

    private void open(ManhuntServices live, CommandSender sender) {
        if (sender instanceof Player viewer) {
            live.screens().sides(viewer);
            return;
        }
        status(live, sender);
    }

    private void join(ManhuntServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "manhunt.join.which-side");
            return;
        }
        String side = args[1].toLowerCase(Locale.ROOT);
        if (live.mode().isRunning()) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return;
        }
        switch (side) {
            case RUNNER -> {
                // The lock is on choosing to run, never on choosing to chase: a server that hand-picks
                // its Runners still wants everybody else to be able to join in without being assigned.
                if (!live.config().runnerSelfJoin() && !sender.hasPermission(PermissionNodes.ADMIN)) {
                    live.messages().send(sender, "manhunt.join.runners-locked");
                    return;
                }
                live.teams().joinRunners(player.getUniqueId());
                live.messages().send(sender, "manhunt.join.runner");
            }
            case HUNTER -> {
                live.teams().joinHunters(player.getUniqueId());
                live.messages().send(sender, "manhunt.join.hunter");
            }
            default -> live.messages().send(sender, "manhunt.join.which-side");
        }
    }

    private void leave(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (live.mode().isRunning()) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return;
        }
        live.teams().leave(player.getUniqueId());
        // Not "you are out of the hunt": everybody racing who is on no side hunts, so leaving the
        // Runners is joining the pack. Saying anything else would be a lie the next start proves.
        live.messages().send(sender, "manhunt.left");
    }

    private void assign(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "manhunt.assign.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "manhunt.no-such-player", "player", args[1]);
            return;
        }
        if (live.mode().isRunning()) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return;
        }
        String side = args[2].toLowerCase(Locale.ROOT);
        Teams.MembershipChange change = switch (side) {
            case RUNNER -> live.teams().joinRunners(target.getUniqueId());
            case HUNTER -> live.teams().joinHunters(target.getUniqueId());
            default -> null;
        };
        if (change == null) {
            live.messages().send(sender, "manhunt.assign.usage");
            return;
        }
        live.messages().send(sender, "manhunt.assign.done", "player", target.getName(), "side", side);
        live.messages().send(target, RUNNER.equals(side) ? "manhunt.join.runner" : "manhunt.join.hunter");
    }

    private void status(ManhuntServices live, CommandSender sender) {
        Hunt hunt = live.mode().current().orElse(null);
        if (hunt == null) {
            live.messages().send(sender, "manhunt.status.waiting",
                    "runners", names(live.teams().runners()),
                    "hunters", names(live.teams().hunters()));
            return;
        }
        live.messages().send(sender, "manhunt.status.running",
                "runners", names(hunt.livingRunners()),
                "out", String.valueOf(hunt.eliminated().size()),
                "hunters", String.valueOf(hunt.hunters().size()));
    }

    /** Names rather than ids, and "nobody" rather than an empty line nobody can read. */
    static String names(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            OfflinePlayer who = Bukkit.getOfflinePlayer(id);
            names.add(who.getName() == null ? "somebody" : who.getName());
        }
        return names.isEmpty() ? "nobody" : String.join(", ", names);
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("join", "leave", "assign", "status").stream()
                    .filter(word -> word.startsWith(typed))
                    .toList();
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        if (word.equals("join") && args.length == 2) {
            return sides(args[1]);
        }
        if (word.equals("assign")) {
            if (args.length == 2) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT)
                                .startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .limit(50)
                        .toList();
            }
            if (args.length == 3) {
                return sides(args[2]);
            }
        }
        return List.of();
    }

    private static List<String> sides(String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return List.of(RUNNER, HUNTER).stream().filter(side -> side.startsWith(prefix)).toList();
    }

    @Override
    public String describe() {
        return "pick a side for the next hunt, or see who is on which";
    }
}
