package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /speedrun} — the one door.
 *
 * <p>Bare, it takes a player who is somewhere else to the lobby (with a button for the menu), and
 * opens the menu for one already there. Every admin action has a word after it too, for those who
 * would rather type — and every word is tab-completed, so nobody has to remember any of them:
 * {@code menu}, {@code join}, {@code start}, {@code check}, {@code resume [time]}, {@code time <time>},
 * {@code reset}, {@code setup}, {@code stats [player]}, {@code top}, {@code history [player]},
 * {@code hud [where]}, {@code seed [seed|random|same]}, {@code spectate}, {@code help}.
 */
public final class SpeedrunJoinCommand implements ISpeedrunCommand {

    /** Each word, whether it is staff's, and what it does — the help page and tab completion read this. */
    record Word(String name, SpeedrunAccess access, String usage, String what) {

        /** Whether the word is staff's — shown to staff only. */
        boolean staff() {
            return PermissionNodes.ADMIN.equals(access.node());
        }
    }

    static final List<Word> WORDS = List.of(
            new Word("menu", SpeedrunAccess.VIEW, "menu", "open the speedrun menu"),
            new Word("join", SpeedrunAccess.VIEW, "join", "go to the lobby"),
            new Word("start", SpeedrunAccess.START, "start", "check everything and start a run"),
            new Word("check", SpeedrunAccess.VIEW, "check", "what a start needs, with fixes"),
            new Word("stats", SpeedrunAccess.VIEW, "stats [player]", "personal bests and runs"),
            new Word("top", SpeedrunAccess.VIEW, "top", "the leaderboard"),
            new Word("history", SpeedrunAccess.VIEW, "history [player]", "past runs, every split"),
            new Word("hud", SpeedrunAccess.HUD, "hud [sidebar|bossbar|actionbar|off]", "where you see your splits"),
            new Word("spectate", SpeedrunAccess.SPECTATE, "spectate", "switch between racing and not"),
            new Word("resume", SpeedrunAccess.RESUME, "resume [time]", "pick a run up after a restart"),
            new Word("time", SpeedrunAccess.SET_CLOCK, "time <time>", "set the running clock"),
            new Word("reset", SpeedrunAccess.RESET, "reset", "remake the run's worlds"),
            new Word("seed", SpeedrunAccess.SEEDS, "seed [seed|random|same]", "the next world's seed"),
            new Word("setup", SpeedrunAccess.SETUP, "setup", "the setup assistant"),
            new Word("help", SpeedrunAccess.VIEW, "help", "this list"));

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunJoinCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            bare(live, sender);
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        Optional<Word> known = WORDS.stream().filter(w -> w.name().equals(word)).findFirst();
        if (known.isEmpty()) {
            live.messages().send(sender, "speedrun.command.unknown", "word", args[0]);
            help(live, sender);
            return;
        }
        // The node of the command the word duplicates — the console holds every node. The start's
        // own rule (staff-only or not) is a question for a player, answered below.
        SpeedrunAccess access = known.get().access();
        String node = access.node();
        if (node != null && access != SpeedrunAccess.START && !sender.hasPermission(node)) {
            live.messages().send(sender, access.staff() ? "speedrun.command.staff-only"
                    : "speedrun.command.no-permission", "word", word);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        SpeedrunLobby lobby = live.lobby();
        SpeedrunActions actions = new SpeedrunActions(lobby, live.messages());
        switch (word) {
            case "menu" -> withPlayer(live, sender, player -> openHub(live, player));
            case "join" -> withPlayer(live, sender, player -> teleport(live, player));
            case "start" -> {
                if (sender instanceof Player player) {
                    if (!SpeedrunAccess.START.allows(lobby, player)) {
                        live.messages().send(player, "speedrun.start.not-allowed");
                        return;
                    }
                    new SpeedrunPreflightMenu(lobby, player, null).open();
                } else {
                    actions.start(sender);
                }
            }
            case "check" -> check(live, sender);
            case "stats" -> stats(live, sender, rest);
            case "top" -> top(live, sender);
            case "history" -> withPlayer(live, sender, player -> target(sender, rest).ifPresentOrElse(
                    who -> new SpeedrunHistoryMenu(lobby, player, null, who.getUniqueId(), nameOf(who)).open(),
                    () -> live.messages().send(sender, "speedrun.command.no-such-player", "player", rest[0])));
            case "hud" -> withPlayer(live, sender, player -> {
                if (rest.length == 0) {
                    actions.cycleHud(player);
                    return;
                }
                try {
                    actions.setHud(player, SpeedrunHudMode.valueOf(rest[0].toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException notOne) {
                    live.messages().send(player, "speedrun.hud.unknown", "place", rest[0]);
                }
            });
            case "spectate" -> withPlayer(live, sender, player -> live.messages().send(player,
                    lobby.toggleSpectator(player.getUniqueId()) ? "speedrun.spectate.on" : "speedrun.spectate.off"));
            case "resume" -> {
                Optional<Duration> time = rest.length == 0 ? Optional.of(Duration.ZERO) : RunClock.parse(rest[0]);
                if (time.isEmpty()) {
                    live.messages().send(sender, "speedrun.time.unreadable", "time", rest[0]);
                    return;
                }
                actions.resume(sender, time.get());
            }
            case "time" -> {
                if (rest.length == 0) {
                    live.messages().send(sender, "speedrun.time.usage");
                    return;
                }
                RunClock.parse(rest[0]).ifPresentOrElse(time -> actions.setClock(sender, time),
                        () -> live.messages().send(sender, "speedrun.time.unreadable", "time", rest[0]));
            }
            case "reset" -> {
                if (sender instanceof Player player) {
                    actions.confirmReset(player, null);
                } else if (rest.length > 0 && rest[0].equalsIgnoreCase("confirm")) {
                    actions.reset(sender);
                } else {
                    live.messages().send(sender, "speedrun.reset.console-confirm");
                }
            }
            case "seed" -> seed(live, sender, rest);
            case "setup" -> withPlayer(live, sender, player -> new SpeedrunSetupMenu(lobby, player, null, 0).open());
            default -> help(live, sender);
        }
    }

    /** {@code /speedrun} alone: to the lobby from elsewhere, the menu from inside the run's worlds. */
    private void bare(SpeedrunAdminServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "speedrun.join.only-a-player");
            return;
        }
        World here = player.getWorld();
        if (here != null && SpeedrunWorlds.around(live.lobby().config().worldName()).contains(here.getName())) {
            openHub(live, player);
            return;
        }
        if (teleport(live, player)) {
            ChatButtons buttons = live.lobby().toolkit().map(SpeedrunToolkit::buttons).orElse(null);
            if (buttons != null) {
                player.sendMessage(live.messages().prefixed("speedrun.join.welcome").append(Component.text(" "))
                        .append(buttons.row(buttons.label("<aqua>[Open the menu]</aqua>")
                                .tooltip("<gray>Everything the lobby does, one click each")
                                .runs("/speedrun menu"))));
            }
        }
    }

    private boolean teleport(SpeedrunAdminServices live, Player player) {
        String worldName = live.lobby().config().worldName();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            live.messages().send(player, "speedrun.join.world-missing", "world", worldName);
            return false;
        }
        player.teleportAsync(world.getSpawnLocation());
        return true;
    }

    private void openHub(SpeedrunAdminServices live, Player player) {
        new SpeedrunLobbyMenu(live.lobby(), live.messages(), SpeedrunScreens.brandOf(live.lobby()), player, null).open();
    }

    private void withPlayer(SpeedrunAdminServices live, CommandSender sender, Consumer<Player> then) {
        if (sender instanceof Player player) {
            then.accept(player);
        } else {
            live.messages().send(sender, "speedrun.command.only-a-player");
        }
    }

    /** The pre-flight checks in chat, each red one with its fix as a button. */
    private void check(SpeedrunAdminServices live, CommandSender sender) {
        new SpeedrunActions(live.lobby(), live.messages()).checkInWords(sender);
    }

    private void stats(SpeedrunAdminServices live, CommandSender sender, String[] rest) {
        SpeedrunHistory history = live.lobby().toolkit().map(SpeedrunToolkit::history).orElse(null);
        Optional<OfflinePlayer> whose = target(sender, rest);
        if (whose.isEmpty()) {
            live.messages().send(sender, rest.length == 0 ? "speedrun.command.only-a-player"
                    : "speedrun.command.no-such-player", "player", rest.length == 0 ? "" : rest[0]);
            return;
        }
        if (history == null) {
            live.messages().send(sender, "speedrun.stats.none", "player", nameOf(whose.get()));
            return;
        }
        UUID id = whose.get().getUniqueId();
        if (sender instanceof Player player) {
            new SpeedrunStatsMenu(live.lobby(), player, null, id, nameOf(whose.get())).open();
            return;
        }
        List<SpeedrunRunRecord> runs = history.runsOf(id);
        if (runs.isEmpty()) {
            live.messages().send(sender, "speedrun.stats.none", "player", nameOf(whose.get()));
            return;
        }
        live.messages().send(sender, "speedrun.stats.header", "player", nameOf(whose.get()),
                "runs", String.valueOf(runs.size()),
                "finished", String.valueOf(runs.stream().filter(SpeedrunRunRecord::completed).count()));
        for (SpeedrunCategory category : history.categories()) {
            history.personalBest(id, category).ifPresent(best -> live.messages().send(sender, "speedrun.stats.best",
                    "category", category.label(), "time", SpeedrunTimerDisplay.plain(best.time()),
                    "record", history.record(category).map(r -> SpeedrunTimerDisplay.plain(r.time())).orElse("-")));
        }
    }

    private void top(SpeedrunAdminServices live, CommandSender sender) {
        if (sender instanceof Player player) {
            new SpeedrunLeaderboardMenu(live.lobby(), player, null, null).open();
            return;
        }
        SpeedrunHistory history = live.lobby().toolkit().map(SpeedrunToolkit::history).orElse(null);
        SpeedrunCategory category = SpeedrunLeaderboardMenu.currentCategory(live.lobby());
        List<SpeedrunRunRecord> board = history == null ? List.of()
                : history.leaderboard(new SpeedrunHistory.Filter(category, SpeedrunHistory.Filter.ANY_COUNT));
        live.messages().send(sender, "speedrun.top.header", "category", category.label());
        for (int place = 0; place < Math.min(10, board.size()); place++) {
            SpeedrunRunRecord run = board.get(place);
            live.messages().send(sender, "speedrun.top.line", "place", String.valueOf(place + 1),
                    "time", SpeedrunTimerDisplay.plain(run.time()),
                    "players", String.join(", ", run.participants().values()));
        }
        if (board.isEmpty()) {
            live.messages().send(sender, "speedrun.top.empty");
        }
    }

    private void seed(SpeedrunAdminServices live, CommandSender sender, String[] rest) {
        SpeedrunLobby lobby = live.lobby();
        if (rest.length == 0) {
            if (sender instanceof Player player) {
                new SpeedrunSeedMenu(lobby, player, null).open();
            } else {
                World world = Bukkit.getWorld(lobby.config().worldName());
                live.messages().send(sender, "speedrun.seed.this-world",
                        "seed", world == null ? "-" : String.valueOf(world.getSeed()));
            }
            return;
        }
        String typed = String.join(" ", rest);
        if (typed.equalsIgnoreCase("random")) {
            lobby.settings().set("seed-mode", SpeedrunSeedMode.RANDOM.name());
            live.messages().send(sender, "speedrun.fix.random-seed");
        } else if (typed.equalsIgnoreCase("same")) {
            lobby.replaySeedNextReset();
            live.messages().send(sender, "speedrun.seed.same-next");
        } else {
            lobby.settings().set("seed", typed);
            lobby.settings().set("seed-mode", SpeedrunSeedMode.FIXED.name());
            live.messages().send(sender, "speedrun.seed.set", "seed", typed);
        }
    }

    private void help(SpeedrunAdminServices live, CommandSender sender) {
        live.messages().send(sender, "speedrun.command.help-header");
        ChatButtons buttons = live.lobby().toolkit().map(SpeedrunToolkit::buttons).orElse(null);
        for (Word word : WORDS) {
            String node = word.access().node();
            if (node != null && word.access() != SpeedrunAccess.START && !sender.hasPermission(node)) {
                continue;
            }
            Component line = live.messages().get("speedrun.command.help-line", "usage", "/speedrun " + word.usage(),
                    "what", word.what());
            if (buttons != null && sender instanceof Player) {
                ChatButton button = buttons.label("<aqua>[Try]</aqua>").suggests("/speedrun " + word.name() + " ");
                line = line.append(Component.text(" ")).append(buttons.row(button));
            }
            sender.sendMessage(line);
        }
    }

    /** The named player, or the sender themselves for no name. */
    private static Optional<OfflinePlayer> target(CommandSender sender, String[] rest) {
        if (rest.length == 0) {
            return sender instanceof Player player ? Optional.of(player) : Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(rest[0]);
        if (online != null) {
            return Optional.of(online);
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(rest[0]);
        return Optional.ofNullable(known);
    }

    private static String nameOf(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return WORDS.stream()
                    .filter(word -> word.access().node() == null || word.access() == SpeedrunAccess.START
                            || sender.hasPermission(word.access().node()))
                    .map(Word::name)
                    .filter(name -> name.startsWith(typed)).toList();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "resume", "time" -> admin ? List.of("0", "42:05", "1:02:03", "1h30m") : List.of();
            case "stats", "history" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case "hud" -> Arrays.stream(SpeedrunHudMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList();
            case "seed" -> admin ? List.of("random", "same") : List.of();
            case "reset" -> admin && !(sender instanceof Player) ? List.of("confirm") : List.of();
            default -> List.of();
        };
        List<String> matching = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                matching.add(option);
            }
        }
        return args.length == 2 ? matching : List.of();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.JOIN;
    }

    @Override
    public String describe() {
        return "the speedrun menu, and every speedrun action by name";
    }
}
