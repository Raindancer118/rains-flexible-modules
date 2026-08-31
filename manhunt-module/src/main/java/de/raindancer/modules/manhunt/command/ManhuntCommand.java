package de.raindancer.modules.manhunt.command;

import de.raindancer.core.content.achievement.Achievement;
import de.raindancer.core.social.team.Teams;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ChaosAction;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.ChaosService;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.SpeedrunSeed;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.core.world.time.Times;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /manhunt} — joining a side, starting and stopping a hunt, resetting the map, and throwing a
 * chaos action at whatever is going, all from the console-compatible command as much as from a menu.
 *
 * <p>See {@code ChainCommand}'s own javadoc on why an unknown word falls through to {@link #help}
 * rather than silently doing nothing.
 */
public final class ManhuntCommand implements IManhuntCommand {

    private final Supplier<ManhuntServices> services;

    public ManhuntCommand(Supplier<ManhuntServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ManhuntServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            lobbyOrStatus(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> join(live, sender, args);
            case "leave" -> leave(live, sender);
            case "assign" -> assign(live, sender, args);
            case "start" -> start(live, sender);
            case "stop" -> stop(live, sender);
            case "reset" -> reset(live, sender, args);
            case "status" -> status(live, sender);
            case "chaos" -> chaos(live, sender, args);
            case "achievements" -> achievements(live, sender);
            case "options" -> options(live, sender);
            case "setlobby" -> setlobby(live, sender);
            default -> help(live, sender);
        }
    }

    private void help(ManhuntServices live, CommandSender sender) {
        live.messages().lines("manhunt.help").forEach(sender::sendMessage);
    }

    private void lobbyOrStatus(ManhuntServices live, CommandSender sender) {
        if (sender instanceof Player player) {
            live.screens().lobby(player);
            return;
        }
        status(live, sender);
    }

    private void status(ManhuntServices live, CommandSender sender) {
        ManhuntTeams teams = live.manhunt().teams();
        java.util.Optional<SpeedrunSession> session = live.manhunt().session();
        String runners = String.valueOf(teams.runners().size());
        String hunters = String.valueOf(teams.hunters().size());
        if (session.isEmpty()) {
            live.messages().send(sender, "manhunt.status.idle", "runners", runners, "hunters", hunters);
            return;
        }
        live.messages().send(sender, "manhunt.status.running",
                "runners", runners, "hunters", hunters, "time", Times.brief(session.get().elapsed()));
    }

    private void join(ManhuntServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "manhunt.usage.join");
            return;
        }
        String side = args[1].toLowerCase(Locale.ROOT);
        boolean joiningRunners = side.equals("runner") || side.equals("runners");
        if (joiningRunners && !live.config().runnerSelfJoinEnabled()
                && !sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.join-refused.runners-locked");
            return;
        }
        Teams.MembershipChange change = switch (side) {
            case "runner", "runners" -> live.manhunt().teams().joinRunners(player.getUniqueId());
            case "hunter", "hunters" -> live.manhunt().teams().joinHunters(player.getUniqueId());
            default -> null;
        };
        if (change == null) {
            live.messages().send(sender, "manhunt.usage.join");
            return;
        }
        if (!change.status().isSuccess()) {
            live.messages().send(sender, "manhunt.join-refused." + change.status().key());
            return;
        }
        live.lobbyListener().relocateIfWaiting(player, live.manhunt().isRunning());
        live.messages().send(sender, "manhunt.joined", "side", side);
    }

    private void leave(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (live.manhunt().teams().leave(player.getUniqueId()).isEmpty()) {
            live.messages().send(sender, "manhunt.not-on-a-side");
            return;
        }
        live.lobbyListener().releaseIfHeld(player);
        live.messages().send(sender, "manhunt.left");
    }

    /** {@code /manhunt assign <player> <runner|hunter>} — an admin's own explicit action, which
     *  bypasses {@link ManhuntSettings#runnerSelfJoinEnabled()} entirely on purpose. */
    private void assign(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "manhunt.usage.assign");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "manhunt.usage.assign");
            return;
        }
        String side = args[2].toLowerCase(Locale.ROOT);
        Teams.MembershipChange change = switch (side) {
            case "runner", "runners" -> live.manhunt().teams().joinRunners(target.getUniqueId());
            case "hunter", "hunters" -> live.manhunt().teams().joinHunters(target.getUniqueId());
            default -> null;
        };
        if (change == null) {
            live.messages().send(sender, "manhunt.usage.assign");
            return;
        }
        if (!change.status().isSuccess()) {
            live.messages().send(sender, "manhunt.join-refused." + change.status().key());
            return;
        }
        live.lobbyListener().relocateIfWaiting(target, live.manhunt().isRunning());
        live.messages().send(sender, "manhunt.joined", "side", side);
    }

    /** {@code /manhunt setlobby} — captures where the sender is standing right now. */
    private void setlobby(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        org.bukkit.Location here = player.getLocation();
        String world = here.getWorld() == null ? "" : here.getWorld().getName();
        live.store().set("lobby-spawn-set", "true");
        live.store().set("lobby-world-name", world);
        live.store().set("lobby-x", String.valueOf(here.getX()));
        live.store().set("lobby-y", String.valueOf(here.getY()));
        live.store().set("lobby-z", String.valueOf(here.getZ()));
        live.store().set("lobby-yaw", String.valueOf(here.getYaw()));
        live.store().save();
        live.messages().send(sender, "manhunt.lobby-set");
    }

    private void start(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        ManhuntService.StartOutcome outcome = live.manhunt().start();
        live.messages().send(sender, "manhunt.start." + outcome.name().toLowerCase(Locale.ROOT));
    }

    private void stop(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (!live.manhunt().stop()) {
            live.messages().send(sender, "manhunt.stop-refused");
            return;
        }
        live.messages().send(sender, "manhunt.stopped");
    }

    private void reset(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        SpeedrunSeed seed = null;
        if (args.length >= 3 && args[1].equalsIgnoreCase("seed")) {
            seed = args[2].equalsIgnoreCase("random") ? SpeedrunSeed.random() : parseSeed(args[2]);
            if (seed == null) {
                live.messages().send(sender, "manhunt.usage.reset");
                return;
            }
        } else if (args.length == 2) {
            live.messages().send(sender, "manhunt.usage.reset");
            return;
        }
        SpeedrunSeed resolvedSeed = seed;
        live.manhunt().resetWorld(resolvedSeed, done ->
                live.messages().send(sender, done ? "manhunt.reset-done" : "manhunt.reset-refused"));
    }

    private static SpeedrunSeed parseSeed(String text) {
        try {
            return SpeedrunSeed.fixed(Long.parseLong(text));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void chaos(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.CHAOS)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 2) {
            if (sender instanceof Player player) {
                live.screens().chaos(player);
                return;
            }
            live.messages().send(sender, "manhunt.usage.chaos");
            return;
        }
        ChaosAction action;
        try {
            action = ChaosAction.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAnAction) {
            live.messages().send(sender, "manhunt.usage.chaos");
            return;
        }
        ChaosService.Result result = live.chaos().apply(action);
        if (result == ChaosService.Result.APPLIED) {
            live.achievements().progressChaos(sender instanceof Player player ? player : null);
        }
        live.messages().send(sender, "manhunt.chaos." + result.name().toLowerCase(Locale.ROOT),
                "action", action.label());
    }

    /** {@code /manhunt achievements} — the curated menu for a player, a full chat listing for console. */
    private void achievements(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.USE)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (sender instanceof Player player) {
            live.screens().achievements(player);
            return;
        }
        ManhuntAchievements achievements = live.achievements();
        List<Achievement> visible = achievements.visibleList();
        live.messages().send(sender, "manhunt.achievements.header",
                "count", String.valueOf(visible.size()),
                "hidden", String.valueOf(achievements.hiddenCount()));
        for (Achievement achievement : visible) {
            live.messages().send(sender, "manhunt.achievements.entry",
                    "title", achievement.title(),
                    "description", achievement.description(),
                    "points", String.valueOf(achievement.points()));
        }
    }

    /** {@code /manhunt options} — the curated settings menu; console is pointed at {@code /settings}. */
    private void options(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (sender instanceof Player player) {
            live.screens().options(player);
            return;
        }
        live.messages().send(sender, "manhunt.options.console-only");
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return startingWith(
                    List.of("join", "leave", "assign", "start", "stop", "reset", "status", "chaos",
                            "achievements", "options", "setlobby"), typed);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            return startingWith(List.of("runner", "hunter"), args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("assign")) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).collect(Collectors.toList());
            return startingWith(names, args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("assign")) {
            return startingWith(List.of("runner", "hunter"), args[2].toLowerCase(Locale.ROOT));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("chaos")) {
            List<String> names = Arrays.stream(ChaosAction.values())
                    .map(a -> a.name().toLowerCase(Locale.ROOT)).toList();
            return startingWith(names, args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return startingWith(List.of("seed"), args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            return startingWith(List.of("random"), args[2].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private static Collection<String> startingWith(List<String> options, String typed) {
        return options.stream()
                .filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "joining a side, starting and stopping a hunt, and throwing chaos at one";
    }
}
