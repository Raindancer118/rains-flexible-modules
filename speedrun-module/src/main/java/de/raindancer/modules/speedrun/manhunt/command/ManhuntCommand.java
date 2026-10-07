package de.raindancer.modules.speedrun.manhunt.command;

import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.speedrun.util.TargetPick;
import de.raindancer.modules.speedrun.SpeedrunBoard;
import de.raindancer.modules.speedrun.manhunt.ManhuntServices;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.setup.HuntDesk;
import de.raindancer.modules.speedrun.manhunt.setup.Preset;
import de.raindancer.modules.speedrun.manhunt.stats.HuntSummary;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import de.raindancer.modules.speedrun.manhunt.stats.StatsFormat;
import de.raindancer.modules.speedrun.manhunt.tracker.CompassHandout;
import de.raindancer.modules.speedrun.manhunt.tracker.TrailPreference;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.RunClock;
import de.raindancer.modules.speedrun.SpeedrunControl;
import de.raindancer.modules.speedrun.SpeedrunActions;
import de.raindancer.modules.speedrun.SpeedrunGoals;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunLobby;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunSplitAnnouncer;
import de.raindancer.modules.speedrun.SpeedrunToolkit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    private final Lobby lobby;

    /** The speedrun lobby, as far as this command drives it — always in Manhunt's own mode. */
    interface Lobby {
        Optional<SpeedrunLobby.GoalRemoval> removeGoal();

        Optional<SpeedrunControl.Answer> start();

        Optional<SpeedrunControl.Answer> resume(Duration already);
    }

    private static final Lobby REAL = new Lobby() {
        @Override
        public Optional<SpeedrunLobby.GoalRemoval> removeGoal() {
            return SpeedrunControl.removeGoal();
        }

        @Override
        public Optional<SpeedrunControl.Answer> start() {
            return SpeedrunControl.start(ManhuntMode.ID);
        }

        @Override
        public Optional<SpeedrunControl.Answer> resume(Duration already) {
            return SpeedrunControl.resume(already, ManhuntMode.ID);
        }
    };

    public ManhuntCommand(Supplier<ManhuntServices> services) {
        this(services, REAL);
    }

    ManhuntCommand(Supplier<ManhuntServices> services, Lobby lobby) {
        this.services = services;
        this.lobby = lobby;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ManhuntServices live = services.get();
        CommandSender sender = source.getSender();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (word) {
            case "", "hub" -> open(live, sender);
            case "sides" -> sides(live, sender);
            case "preflight" -> preflight(live, sender);
            case "balance" -> balance(live, sender);
            case "random" -> random(live, sender, args);
            case "unassign" -> unassign(live, sender, args);
            case "door" -> door(live, sender, args);
            case "setup" -> setup(live, sender, args);
            case "stats" -> stats(live, sender, args);
            case "top" -> top(live, sender, args);
            case "history" -> history(live, sender);
            case "summary" -> summary(live, sender, args);
            case "hud" -> hud(live, sender);
            case "announcements" -> announcements(live, sender);
            case "join" -> join(live, sender, args);
            case "leave" -> leave(live, sender);
            case "assign" -> assign(live, sender, args);
            case "reset" -> reset(live, sender);
            case "status" -> status(live, sender);
            case "trail" -> trail(live, sender);
            case "here" -> here(live, sender, args);
            case "give" -> give(live, sender, args);
            case "goal" -> goal(live, sender, args);
            case "start" -> start(live, sender);
            case "resume" -> resume(live, sender, args);
            default -> {
                List<String> mine = new ArrayList<>(PLAYER_WORDS);
                if (sender.hasPermission(PermissionNodes.ADMIN)) {
                    mine.addAll(ADMIN_WORDS);
                }
                List<String> guesses = Closest.to(word, mine, 1);
                if (guesses.isEmpty()) {
                    live.messages().send(sender, "manhunt.unknown-word", "word", word);
                } else {
                    live.messages().send(sender, "manhunt.did-you-mean", "word", word, "guess", guesses.getFirst());
                }
            }
        }
    }

    /** {@code /manhunt start} — what the lobby's start block does, with the lobby set to Manhunt. */
    private void start(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        say(live, sender, lobby.start(), "speedrun.start.started", Duration.ZERO);
    }

    /**
     * {@code /manhunt resume [time]} — a hunt picked up over the world as it stands, after a restart:
     * see {@code SpeedrunLobby.resume}. The Runners must be on their side first; everybody else hunts.
     */
    private void resume(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 2 && sender instanceof Player viewer) {
            live.screens().askResumeTime(viewer);
            return;
        }
        Duration already = Duration.ZERO;
        if (args.length > 1) {
            Optional<Duration> parsed = RunClock.parse(args[1]);
            if (parsed.isEmpty()) {
                live.messages().send(sender, "speedrun.time.unreadable", "time", args[1]);
                return;
            }
            already = parsed.get();
        }
        say(live, sender, lobby.resume(already), "manhunt.resume.done", already);
    }

    private void say(ManhuntServices live, CommandSender sender, Optional<SpeedrunControl.Answer> answer,
                     String startedKey, Duration at) {
        if (answer.isEmpty()) {
            live.messages().send(sender, "manhunt.goal.no-lobby");
            return;
        }
        SpeedrunControl.Answer said = answer.get();
        long seconds = at.getSeconds();
        live.messages().send(sender, said.started() ? startedKey : said.messageKey(),
                "players", String.valueOf(said.players()),
                "time", "%d:%02d".formatted(seconds / 60, seconds % 60),
                "mode", ManhuntMode.ID);
        if (!said.started()) {
            // Whatever the lobby refused, the pre-flight page names it with its fix.
            live.messages().send(sender, "manhunt.start.what-is-missing");
        }
    }

    /**
     * {@code /manhunt goal [remove|set <advancement>]} — the goal page with nothing after it; remove
     * works mid-hunt too, which then ends by catching or by reset; set is for the next hunt.
     */
    private void goal(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 2 && sender instanceof Player viewer) {
            live.screens().open(viewer, ManhuntServices.Page.GOAL);
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
            if (live.desk().setGoal(args[2])) {
                live.messages().send(sender, live.mode().isRunning() ? "manhunt.goal.set-next" : "manhunt.goal.set",
                        "goal", SpeedrunGoals.byKey(args[2]).map(SpeedrunGoals.Goal::label).orElse(args[2]));
            } else {
                live.messages().send(sender, "manhunt.goal.unknown", "goal", args[2]);
            }
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("remove")) {
            live.messages().send(sender, "manhunt.goal.usage");
            return;
        }
        String key = lobby.removeGoal().map(removal -> switch (removal) {
            case NONE_SET -> "manhunt.goal.none-set";
            case REMOVED -> "manhunt.goal.removed";
            case REMOVED_FROM_RUN -> "manhunt.goal.removed-mid-hunt";
        }).orElse("manhunt.goal.no-lobby");
        live.messages().send(sender, key);
    }

    /** {@code /manhunt give <player|all> [tracker|team|structure]} — a lost compass back, see CompassHandout. */
    private void give(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "manhunt.give.usage");
            return;
        }
        Optional<CompassHandout.Kind> kind = Optional.empty();
        if (args.length > 2) {
            kind = CompassHandout.Kind.parse(args[2]);
            if (kind.isEmpty()) {
                live.messages().send(sender, "manhunt.give.unknown-kind", "compass", args[2]);
                return;
            }
        }
        if (args[1].equalsIgnoreCase("all")) {
            live.compasses().giveEverybody(sender, kind);
            return;
        }
        // A selector may name a whole group, so this one loops.
        PlayerLookup found = PlayerTargets.lookup(Bukkit.getServer(), sender, args[1]);
        if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
            live.messages().send(sender, "manhunt.selector-refused", "player", args[1]);
            return;
        }
        if (found.isEmpty()) {
            live.messages().send(sender, "manhunt.no-such-player", "player", args[1]);
            return;
        }
        if (found.isOfflineOnly()) {
            live.messages().send(sender, "manhunt.player-offline",
                    "player", PlayerTargets.shownName(found.matches().getFirst()));
            return;
        }
        for (Player target : found.online()) {
            live.compasses().give(sender, target, kind);
        }
    }

    /** Says where you are to everybody, the coordinates a button that walks the clicker there. */
    private void here(ManhuntServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
            live.messages().send(player, live.share().stop(player)
                    ? "manhunt.here.stopped" : "manhunt.here.not-navigating");
            return;
        }
        live.share().share(player);
    }

    /** Each player's own particle trail on or off — see TrailPreference. */
    private void trail(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        live.messages().send(player, TrailPreference.messageKey(TrailPreference.toggle(player, live.config())));
    }

    private void open(ManhuntServices live, CommandSender sender) {
        if (sender instanceof Player viewer) {
            live.screens().open(viewer, ManhuntServices.Page.HUB);
            return;
        }
        status(live, sender);
    }

    private static boolean admin(ManhuntServices live, CommandSender sender) {
        if (sender.hasPermission(PermissionNodes.ADMIN)) {
            return true;
        }
        live.messages().send(sender, "manhunt.not-yours");
        return false;
    }

    /** An admin's page for a player, or {@code inWords} for the console. */
    private static void page(ManhuntServices live, CommandSender sender, ManhuntServices.Page page, Runnable inWords) {
        if (!admin(live, sender)) {
            return;
        }
        if (sender instanceof Player viewer) {
            live.screens().open(viewer, page);
        } else {
            inWords.run();
        }
    }

    /** {@code /manhunt sides}: the editor for an admin; anybody else picks their own side on the hub. */
    private void sides(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player viewer)) {
            status(live, sender);
            return;
        }
        live.screens().open(viewer, sender.hasPermission(PermissionNodes.ADMIN)
                ? ManhuntServices.Page.SIDES : ManhuntServices.Page.HUB);
    }

    /** {@code /manhunt preflight}: everything worth knowing before the start, each with its fix. */
    private void preflight(ManhuntServices live, CommandSender sender) {
        page(live, sender, ManhuntServices.Page.PREFLIGHT, () -> preflightInWords(live, sender));
    }

    /** The lobby's own pre-flight check, Manhunt's checks among the rest. */
    static void preflightInWords(ManhuntServices live, CommandSender sender) {
        SpeedrunLobby lobby = live.lobby().get();
        if (lobby == null) {
            live.messages().send(sender, "manhunt.goal.no-lobby");
            return;
        }
        new SpeedrunActions(lobby, live.messages()).checkInWords(sender);
    }

    private void balance(ManhuntServices live, CommandSender sender) {
        if (admin(live, sender)) {
            report(live, sender, live.desk().balance(), "manhunt.balance.done");
        }
    }

    private void random(ManhuntServices live, CommandSender sender, String[] args) {
        if (!admin(live, sender)) {
            return;
        }
        int count = 1;
        if (args.length > 1) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                count = 0;
            }
            if (count < 1) {
                live.messages().send(sender, "manhunt.random.usage");
                return;
            }
        }
        report(live, sender, live.desk().randomRunners(count), "manhunt.random.done");
    }

    private static void report(ManhuntServices live, CommandSender sender, HuntDesk.Result result, String doneKey) {
        if (!result.done()) {
            live.messages().send(sender, result.refusal());
            return;
        }
        live.messages().send(sender, doneKey, "runners", String.join(", ", result.runners()),
                "hunters", String.valueOf(result.hunters()),
                "chance", String.valueOf(Math.round(result.runnersExpected() * 100)));
    }

    /** {@code /manhunt unassign <player>}: off whichever side they were on — before a hunt. */
    private void unassign(ManhuntServices live, CommandSender sender, String[] args) {
        if (!admin(live, sender)) {
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "manhunt.unassign.usage");
            return;
        }
        UUID who = resolve(live, sender, args[1], "manhunt.no-such-player");
        if (who == null) {
            return;
        }
        if (live.mode().isRunning()) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return;
        }
        live.teams().leave(who);
        live.messages().send(sender, "manhunt.unassign.done", "player", args[1]);
    }

    static final TargetPick.Keys PICK_KEYS = new TargetPick.Keys("manhunt.no-such-player",
            "manhunt.player-offline", "manhunt.too-many", "manhunt.selector-refused");

    /**
     * Who {@code typed} means, online or not, or null after telling the sender why not.
     *
     * <p>A real name always beats a nickname: when it is not somebody the server can name right now, the
     * names the hunts remember are asked before a nickname gets to redirect the command.
     */
    static UUID resolve(ManhuntServices live, CommandSender sender, String typed, String nobodyKey) {
        PlayerLookup found = PlayerTargets.lookup(Bukkit.getServer(), sender, typed);
        if (found.kind() == PlayerLookup.Kind.NONE || found.kind() == PlayerLookup.Kind.NICKNAME) {
            Optional<UUID> remembered = live.stats().byName(typed);
            if (remembered.isPresent()) {
                return remembered.get();
            }
        }
        TargetPick pick = TargetPick.anyone(Bukkit.getServer(), sender, typed);
        TargetPick.Keys keys = new TargetPick.Keys(nobodyKey, PICK_KEYS.offline(), PICK_KEYS.tooMany(),
                PICK_KEYS.selectorRefused());
        return pick.tell(live.messages(), sender, keys) ? null : pick.who().getUniqueId();
    }

    /** {@code /manhunt door keep-open|close-on-start}: what a start does to the whitelist. */
    private void door(ManhuntServices live, CommandSender sender, String[] args) {
        if (!admin(live, sender)) {
            return;
        }
        String how = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (how) {
            case "keep-open" -> {
                live.desk().keepDoorOpen();
                live.messages().send(sender, "manhunt.door.kept-open");
            }
            case "close-on-start" -> {
                live.desk().closeDoorOnStart();
                live.messages().send(sender, "manhunt.door.closes");
            }
            default -> live.messages().send(sender, "manhunt.door.usage");
        }
    }

    /** {@code /manhunt setup [preset|skip]}: the wizard, or one of its answers typed straight in. */
    private void setup(ManhuntServices live, CommandSender sender, String[] args) {
        if (!admin(live, sender)) {
            return;
        }
        if (args.length < 2) {
            if (sender instanceof Player viewer) {
                live.screens().open(viewer, ManhuntServices.Page.SETUP);
            } else {
                live.messages().send(sender, "manhunt.setup.usage");
            }
            return;
        }
        if (args[1].equalsIgnoreCase("skip")) {
            // The lobby's one setup — skipping it here skips it everywhere.
            SpeedrunLobby lobby = live.lobby().get();
            if (lobby != null) {
                lobby.settings().set("setup-done", "true");
            }
            live.messages().send(sender, "manhunt.setup.skipped");
            return;
        }
        Optional<Preset> preset = Preset.byId(args[1]);
        if (preset.isEmpty()) {
            live.messages().send(sender, "manhunt.setup.usage");
            return;
        }
        preset.get().applyTo(live.settings());
        live.messages().send(sender, "manhunt.setup.applied", "preset", preset.get().id());
    }

    /** {@code /manhunt stats [player]}: a page for a player, words for the console. */
    private void stats(ManhuntServices live, CommandSender sender, String[] args) {
        UUID whose;
        if (args.length < 2) {
            if (!(sender instanceof Player self)) {
                live.messages().send(sender, "manhunt.stats.usage");
                return;
            }
            whose = self.getUniqueId();
        } else {
            whose = resolve(live, sender, args[1], "manhunt.stats.unknown");
            if (whose == null) {
                return;
            }
            if (!live.stats().has(whose) && Bukkit.getPlayer(whose) == null) {
                live.messages().send(sender, "manhunt.stats.unknown", "player", args[1]);
                return;
            }
        }
        if (sender instanceof Player viewer) {
            live.screens().stats(viewer, whose);
            return;
        }
        PlayerStats stats = live.stats().get(whose);
        live.messages().send(sender, "manhunt.stats.in-words", "player", stats.name(),
                "rating", String.valueOf(Math.round(stats.rating())), "hunts", String.valueOf(stats.hunts()),
                "runner-wins", String.valueOf(stats.runnerWins()), "hunter-wins", String.valueOf(stats.hunterWins()),
                "catches", String.valueOf(stats.catches()), "caught", String.valueOf(stats.timesCaught()),
                "best", HuntSummary.clock(stats.bestSurvivalMillis()),
                "distance", String.valueOf(Math.round(stats.distance())));
    }

    /** {@code /manhunt top [board]}: the leaderboard. */
    private void top(ManhuntServices live, CommandSender sender, String[] args) {
        Optional<SpeedrunBoard> board = args.length < 2 ? Optional.of(SpeedrunBoard.RATING)
                : SpeedrunBoard.byId(args[1]);
        if (board.isEmpty()) {
            live.messages().send(sender, "manhunt.top.usage");
            return;
        }
        if (sender instanceof Player viewer) {
            live.screens().open(viewer, ManhuntServices.Page.LEADERBOARD);
            return;
        }
        List<PlayerStats> top = live.stats().top(board.get(), 10);
        if (top.isEmpty()) {
            live.messages().send(sender, "manhunt.top.empty");
            return;
        }
        live.messages().send(sender, "manhunt.top.header", "board", board.get().id());
        int rank = 1;
        for (PlayerStats entry : top) {
            live.messages().send(sender, "manhunt.top.entry", "rank", String.valueOf(rank++),
                    "player", entry.name(), "value", StatsFormat.value(board.get(), entry));
        }
    }

    /** {@code /manhunt history}: the hunts this server kept. */
    /** The hunts in the lobby's one history, newest first. */
    private static List<SpeedrunRunRecord> hunts(ManhuntServices live) {
        SpeedrunLobby lobby = live.lobby().get();
        SpeedrunHistory history = lobby == null ? null : lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
        if (history == null) {
            return List.of();
        }
        return history.newestFirst().stream().filter(run -> run.category().mode().equals(ManhuntMode.ID)).toList();
    }

    private static int numberOf(ManhuntServices live, SpeedrunRunRecord run) {
        SpeedrunLobby lobby = live.lobby().get();
        return lobby == null ? 0 : lobby.toolkit().map(SpeedrunToolkit::history).map(h -> h.numberOf(run)).orElse(0);
    }

    private void history(ManhuntServices live, CommandSender sender) {
        if (sender instanceof Player viewer) {
            live.screens().open(viewer, ManhuntServices.Page.HISTORY);
            return;
        }
        List<SpeedrunRunRecord> hunts = hunts(live);
        if (hunts.isEmpty()) {
            live.messages().send(sender, "manhunt.history.none");
            return;
        }
        for (SpeedrunRunRecord hunt : hunts.subList(0, Math.min(10, hunts.size()))) {
            live.messages().send(sender, "manhunt.history.entry", "number", String.valueOf(numberOf(live, hunt)),
                    "winner", live.messages().raw("manhunt.history.winner-"
                            + HuntSummary.of(hunt).winner().name().toLowerCase(Locale.ROOT)),
                    "time", HuntSummary.clock(hunt.time().toMillis()));
        }
    }

    /** {@code /manhunt summary [number]}: a run of the history by its number, or the last hunt. */
    private void summary(ManhuntServices live, CommandSender sender, String[] args) {
        String asked = args.length < 2 ? "" : args[1];
        Optional<SpeedrunRunRecord> run;
        if (asked.isEmpty()) {
            run = hunts(live).stream().findFirst();
        } else {
            SpeedrunLobby lobby = live.lobby().get();
            int number;
            try {
                number = Integer.parseInt(asked);
            } catch (NumberFormatException notANumber) {
                number = -1;
            }
            int wanted = number;
            run = lobby == null ? Optional.empty()
                    : lobby.toolkit().map(SpeedrunToolkit::history).flatMap(h -> h.byNumber(wanted));
        }
        if (run.isEmpty()) {
            live.messages().send(sender, "manhunt.summary.none", "number", asked.isEmpty() ? "?" : asked);
            return;
        }
        int number = numberOf(live, run.get());
        if (sender instanceof Player viewer) {
            live.screens().summary(viewer, number);
            return;
        }
        live.chronicle().summaryLines(HuntSummary.of(run.get()), number).forEach(sender::sendMessage);
    }

    /** {@code /manhunt hud}: the lobby's one splits HUD — the next place round, the same as the menu's button. */
    private void hud(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        SpeedrunLobby lobby = live.lobby().get();
        if (lobby == null) {
            live.messages().send(sender, "manhunt.goal.no-lobby");
            return;
        }
        new SpeedrunActions(lobby, live.messages()).cycleHud(player);
    }

    /** {@code /manhunt announcements}: this player's split titles, on or off. */
    private void announcements(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        live.messages().send(player, SpeedrunSplitAnnouncer.TITLES.toggle(player)
                ? "manhunt.announcements.on" : "manhunt.announcements.off");
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
            // Mid-hunt, a join is a side change: it has a compass and a roster to keep in step, and
            // it is refused outright on a server that does not allow them. See ManhuntMode.changeSide.
            ManhuntMode.Side wanted = switch (side) {
                case RUNNER -> ManhuntMode.Side.RUNNER;
                case HUNTER -> ManhuntMode.Side.HUNTER;
                default -> null;
            };
            if (wanted == null) {
                live.messages().send(sender, "manhunt.join.which-side");
                return;
            }
            if (wanted == ManhuntMode.Side.RUNNER && !live.config().runnerSelfJoin()) {
                live.messages().send(sender, "manhunt.join.runners-locked");
                return;
            }
            report(live, sender, player.getName(),
                    live.mode().changeSide(player.getUniqueId(), wanted, false), side);
            return;
        }
        switch (side) {
            case RUNNER -> {
                // The lock is on choosing to run, never on choosing to chase: a server that hand-picks
                // its Runners still wants everybody else to be able to join in without being assigned.
                // No admin exception — when the Runners are hand-picked, they are picked by
                // /manhunt assign, including for an admin choosing themselves. One door, not two.
                if (!live.config().runnerSelfJoin()) {
                    live.messages().send(sender, "manhunt.join.runners-locked");
                    return;
                }
                joinOrSayFrozen(live, sender, player, true);
            }
            case HUNTER -> joinOrSayFrozen(live, sender, player, false);
            default -> live.messages().send(sender, "manhunt.join.which-side");
        }
    }

    /** @return whether they joined — a hunt that began a moment ago has frozen the sides */
    private static boolean joinOrSayFrozen(ManhuntServices live, CommandSender sender, Player who,
                                           boolean runner) {
        if (!live.teams().join(who.getUniqueId(), runner)) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return false;
        }
        live.messages().send(who, runner ? "manhunt.join.runner" : "manhunt.join.hunter");
        return true;
    }

    private void leave(ManhuntServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "manhunt.only-a-player");
            return;
        }
        if (live.mode().isRunning()) {
            switch (live.mode().leaveHunt(player.getUniqueId())) {
                case LEFT -> {
                    live.compasses().takeAll(player);
                    live.messages().send(sender, "manhunt.left-hunt");
                }
                case NOT_IN_THE_HUNT -> live.messages().send(sender, "manhunt.not-in-hunt");
                // Ended between the check and the leave: the lobby's ordinary leave below.
                case NO_HUNT -> {
                    live.teams().leave(player.getUniqueId());
                    live.messages().send(sender, "manhunt.left");
                }
            }
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
        TargetPick pick = TargetPick.online(Bukkit.getServer(), sender, args[1]);
        if (pick.tell(live.messages(), sender, PICK_KEYS)) {
            return;
        }
        Player target = pick.player();
        // Never refused for being mid-hunt: assign is the escape hatch, and the one path that may
        // move somebody while a hunt is being played. It asks first — see assignMidHunt.
        String side = args[2].toLowerCase(Locale.ROOT);
        if (live.mode().isRunning()) {
            assignMidHunt(live, sender, target, side);
            return;
        }
        if (!RUNNER.equals(side) && !HUNTER.equals(side)) {
            live.messages().send(sender, "manhunt.assign.usage");
            return;
        }
        if (joinOrSayFrozen(live, sender, target, RUNNER.equals(side))) {
            live.messages().send(sender, "manhunt.assign.done", "player", target.getName(), "side", side);
        }
    }

    /**
     * {@code /manhunt assign} while a hunt is actually being played — the admin escape hatch that
     * works even where players may not switch for themselves.
     *
     * <h2>Why a player is asked and the console is not</h2>
     * Moving somebody mid-hunt changes the game everybody else is in the middle of: a Runner handed
     * to the Hunters takes their side's chance of winning with them, and the compass and the roster
     * move with them. That is worth one click of "yes" from somebody who may have typed the wrong
     * name. The console has nowhere to show a page and nobody typing at it by accident — a script or
     * an operator at a terminal meant exactly what they wrote, so it is taken as written.
     */
    private void assignMidHunt(ManhuntServices live, CommandSender sender, Player target, String side) {
        ManhuntMode.Side wanted = switch (side) {
            case RUNNER -> ManhuntMode.Side.RUNNER;
            case HUNTER -> ManhuntMode.Side.HUNTER;
            default -> null;
        };
        if (wanted == null) {
            live.messages().send(sender, "manhunt.assign.usage");
            return;
        }
        Runnable move = () -> report(live, sender, target.getName(),
                live.mode().changeSide(target.getUniqueId(), wanted, true), side);
        if (!(sender instanceof Player asking)) {
            move.run();
            return;
        }
        live.messages().send(sender, "manhunt.assign.mid-hunt-warning",
                "player", target.getName(), "side", side);
        live.screens().confirm(asking,
                "<red>Move " + MiniMessage.miniMessage().escapeTags(target.getName()) + " to the " + side + "s?",
                List.of("<gray>A hunt is being played right now.",
                        "<gray>Their team and their compass move with them.",
                        "<dark_gray>Everybody else is in the middle of this round."),
                move);
    }

    /** Says what a mid-hunt side change did, in the hunt's own words rather than a generic refusal. */
    private void report(ManhuntServices live, CommandSender sender, String who,
                        ManhuntMode.SideChange outcome, String side) {
        live.messages().send(sender, outcome.messageKey(), "player", who, "side", side);
    }

    /**
     * {@code /manhunt reset} — both sides emptied, so the next hunt starts from nobody having picked
     * anything. Refused while a hunt is under way: the rosters are what that hunt is being judged by.
     */
    private void reset(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (live.mode().isRunning()) {
            live.messages().send(sender, "manhunt.sides-frozen");
            return;
        }
        int cleared = live.teams().clearBoth();
        live.messages().send(sender, "manhunt.reset.done", "players", String.valueOf(cleared));
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
        boolean admin = source.getSender().hasPermission(PermissionNodes.ADMIN);
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(PLAYER_WORDS);
            if (admin) {
                words.addAll(ADMIN_WORDS);
            }
            return starting(words, args.length == 0 ? "" : args[0]);
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        String typed = args[args.length - 1];
        if (args.length == 2) {
            switch (word) {
                case "here" -> {
                    return starting(List.of("stop"), typed);
                }
                case "join" -> {
                    return sides(typed);
                }
                case "stats" -> {
                    return starting(knownNames(source.getSender(), typed), typed);
                }
                case "top" -> {
                    return starting(Arrays.stream(SpeedrunBoard.values()).map(SpeedrunBoard::id).toList(), typed);
                }
                case "summary" -> {
                    return starting(hunts(services.get()).stream()
                            .map(hunt -> String.valueOf(numberOf(services.get(), hunt))).toList(), typed);
                }
                default -> { }
            }
        }
        if (!admin) {
            return List.of();
        }
        if (args.length == 2) {
            switch (word) {
                case "goal" -> {
                    return starting(List.of("remove", "set"), typed);
                }
                case "door" -> {
                    return starting(List.of("keep-open", "close-on-start"), typed);
                }
                case "setup" -> {
                    List<String> answers = new ArrayList<>(Arrays.stream(Preset.values()).map(Preset::id).toList());
                    answers.add("skip");
                    return starting(answers, typed);
                }
                case "random" -> {
                    return starting(List.of("1", "2", "3"), typed);
                }
                case "resume" -> {
                    return starting(List.of("0:00", "10:00", "30:00", "1:00:00"), typed);
                }
                case "assign", "unassign" -> {
                    return starting(onlineNames(source.getSender(), typed), typed);
                }
                case "give" -> {
                    List<String> targets = new ArrayList<>(List.of("all"));
                    targets.addAll(onlineNames(source.getSender(), typed));
                    return starting(targets, typed);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3) {
            switch (word) {
                case "goal" -> {
                    return args[1].equalsIgnoreCase("set")
                            ? starting(SpeedrunGoals.all().stream().map(SpeedrunGoals.Goal::key).toList(), typed) : List.of();
                }
                case "assign" -> {
                    return sides(typed);
                }
                case "give" -> {
                    return starting(Arrays.stream(CompassHandout.Kind.values()).map(CompassHandout.Kind::word).toList(),
                            typed);
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private static final List<String> PLAYER_WORDS = List.of("hub", "join", "leave", "status", "stats", "top",
            "history", "summary", "trail", "here", "hud", "announcements");
    private static final List<String> ADMIN_WORDS = List.of("sides", "assign", "unassign", "balance", "random",
            "preflight", "start", "resume", "give", "goal", "door", "reset", "setup");

    private static List<String> starting(Collection<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(50).toList();
    }

    private static List<String> onlineNames(CommandSender sender, String typed) {
        return PlayerTargets.suggest(Bukkit.getServer(), sender, typed, who -> true);
    }

    private List<String> knownNames(CommandSender sender, String typed) {
        List<String> names = new ArrayList<>(onlineNames(sender, typed));
        for (PlayerStats stats : services.get().stats().top(SpeedrunBoard.RATING, 200)) {
            if (!names.contains(stats.name())) {
                names.add(stats.name());
            }
        }
        return names;
    }

    private static List<String> sides(String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return List.of(RUNNER, HUNTER).stream().filter(side -> side.startsWith(prefix)).toList();
    }

    @Override
    public String describe() {
        return "pick a side for the next hunt, or see who is on which";
    }

    /** What Paper asks before running this — the node the command is declared with. */
    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
