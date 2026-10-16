package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.Groups;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.voice.VoiceGroups;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.core.world.blocks.Rewind;
import de.raindancer.core.world.combat.Streaks;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Award;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.Building;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.Flag;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.model.Phase;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Round;
import de.raindancer.modules.ctf.model.Side;
import de.raindancer.modules.ctf.rules.AwardRule;
import de.raindancer.modules.ctf.rules.BalanceRule;
import de.raindancer.modules.ctf.rules.BuildRule;
import de.raindancer.modules.ctf.rules.ControlRule;
import de.raindancer.modules.ctf.rules.FlagRule;
import de.raindancer.modules.ctf.rules.WinRule;
import de.raindancer.modules.ctf.store.MapStore;
import de.raindancer.modules.ctf.store.StatsStore;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A round from beginning to end: the lobby filling, the votes, the map, the sides and the flags, everything that happens
 * while it runs, and the results. The round itself is the lock — every change to it is made holding it; Bukkit is only
 * touched on the thread that owns what is touched.
 *
 * <p>Two clocks drive it: {@link #tick()} once a second (countdowns, respawns, flags going home, the control point, the
 * scoreboard) and {@link #fastTick()} five times a second (who touches which flag, who brings one home, trails).
 */
public final class RoundService implements ICtfService {

    /** What the scoreboard and the menus are labelled with. */
    public static final String OWNER = "ctf";

    /** How near a flag somebody must come to touch it, blocks. */
    public static final double TOUCH = 1.6;
    /** Seconds everybody stands still at the start. */
    public static final int FREEZE_SECONDS = 5;
    /** Seconds the results stay up before everybody goes back to the lobby. */
    public static final int RESULTS_SECONDS = 10;
    /** Seconds between swapped bases. */
    public static final int SWAP_SECONDS = 120;
    /** A carrier's teammate this near who makes a kill is escorting. */
    public static final double ESCORT = 8;
    /** A kill this near one's own flag is a defender's. */
    public static final double DEFENCE = 12;

    /** Somebody's way into a round's damage, for credit when they fall off the map. */
    private record Hurt(UUID by, long at) {
    }

    /** A flag being taken from its stand, slowly. */
    private record Taking(int side, long since) {
    }

    /** A trap or a turret an engineer or trapper put down. */
    private record Device(String kind, int side, UUID owner, String world, int x, int y, int z, long until) {
    }

    private final Plugin plugin;
    private final LogChannel log;
    private final Messages messages;
    private final Effects effects;
    private final Scoreboards scoreboards;
    private final BossBars bossBars;
    private final ActionBars actionBars;
    private final MapStore maps;
    private final ArenaWorldService arena;
    private final LobbyService lobby;
    private final FlagService flags;
    private final RoleService roles;
    private final PowerUpService powerUps;
    private final SupplyService supplies;
    private final MutatorService mutators;
    private final AnnouncerService announcer;
    private final ProgressService progress;
    private final PartyService parties;
    private final VoteService votes;
    private final SpectatorService spectators;
    private final LongSupplier clock;
    private final Random random = new Random();

    private final FlagRule flagRule = new FlagRule();
    private final WinRule winRule = new WinRule();
    private final BalanceRule balance = new BalanceRule();
    private final BuildRule buildRule = new BuildRule();
    private final AwardRule awardRule = new AwardRule();
    private final ControlRule controlRule = new ControlRule();

    private final Rewind rewind;
    private final Streaks streaks;
    private final Map<UUID, TeamColour> wishes = new ConcurrentHashMap<>();
    private final Map<UUID, Role> picks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fees = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> helmets = new ConcurrentHashMap<>();
    private final Map<UUID, Taking> taking = new ConcurrentHashMap<>();
    private final Map<UUID, Hurt> lastHurt = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> helpers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> marked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nagged = new ConcurrentHashMap<>();
    private final Set<Long> placed = ConcurrentHashMap.newKeySet();
    private final Set<Integer> flying = ConcurrentHashMap.newKeySet();
    private final Map<Integer, UUID> threwLast = new ConcurrentHashMap<>();
    private final List<Device> devices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<UUID> respawning = ConcurrentHashMap.newKeySet();

    private volatile Round round;
    private volatile Phase gathering = Phase.WAITING;
    private volatile int countdown;
    private volatile long frozenUntil;
    private volatile long overAt;
    private volatile String lastMap;
    private volatile boolean startRequested;
    private volatile CtfSettings settings;

    public RoundService(Plugin plugin, LogChannel log, Messages messages, Effects effects, Scoreboards scoreboards,
                        BossBars bossBars, ActionBars actionBars, MapStore maps, ArenaWorldService arena,
                        LobbyService lobby, FlagService flags, RoleService roles, PowerUpService powerUps,
                        SupplyService supplies, MutatorService mutators, AnnouncerService announcer,
                        ProgressService progress, PartyService parties, VoteService votes,
                        SpectatorService spectators, LongSupplier clock, CtfSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
        this.effects = effects;
        this.scoreboards = scoreboards;
        this.bossBars = bossBars;
        this.actionBars = actionBars;
        this.maps = maps;
        this.arena = arena;
        this.lobby = lobby;
        this.flags = flags;
        this.roles = roles;
        this.powerUps = powerUps;
        this.supplies = supplies;
        this.mutators = mutators;
        this.announcer = announcer;
        this.progress = progress;
        this.parties = parties;
        this.votes = votes;
        this.spectators = spectators;
        this.clock = clock;
        this.settings = settings;
        this.streaks = new Streaks(clock, Duration.ofSeconds(4));
        this.rewind = new Rewind(plugin, this::insideTheMap);
        flags.rewind(rewind);
        powerUps.mayTake((player, kind) -> {
            Round now = round;
            if (now == null || !now.phase().isPlaying()) {
                return false;
            }
            synchronized (now) {
                Optional<Fighter> fighter = now.fighter(player.getUniqueId());
                if (fighter.isEmpty() || !fighter.get().isAlive()) {
                    return false;
                }
                fighter.get().tookPowerUp();
                progress.deed(player.getUniqueId(), player.getName(), ProgressService.Deed.POWER_UP,
                        fighter.get().role(), 1);
                return true;
            }
        });
        powerUps.onDoubleJump(player -> {
            Round now = round;
            if (now != null) {
                synchronized (now) {
                    now.fighter(player.getUniqueId()).ifPresent(fighter -> fighter.doubleJumps(3));
                }
            }
        });
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public CtfSettings config() {
        return settings;
    }

    public Rewind rewind() {
        return rewind;
    }

    public Optional<Round> round() {
        return Optional.ofNullable(round);
    }

    /** Where things stand: the round's phase while there is one, else the lobby's. */
    public Phase phase() {
        Round now = round;
        return now == null ? gathering : now.phase();
    }

    public int countdown() {
        return countdown;
    }

    public boolean isFrozen() {
        return clock.getAsLong() < frozenUntil;
    }

    // ================================================================== the lobby

    /** Into Capture the Flag: the lobby, or straight into the running round. Their own thread. */
    public void join(Player player) {
        if (lobby.isInside(player.getUniqueId()) && (round == null || isIn(player.getUniqueId()))) {
            messages.send(player, "ctf.join.already");
            return;
        }
        lobby.enter(player);
        progress.load(player.getUniqueId());
        progress.profile(player.getUniqueId());
        messages.send(player, "ctf.join.welcome");
        Round now = round;
        if (now != null && now.phase().isPlaying()) {
            if (settings.joinRunning()) {
                Scheduling.globalLater(plugin, 10L, () -> joinLate(player));
            } else {
                Scheduling.globalLater(plugin, 10L, () -> watch(player));
            }
        }
    }

    /** Out of Capture the Flag altogether. Their own thread. */
    public void leave(Player player) {
        UUID id = player.getUniqueId();
        removeFromRound(id, true);
        spectators.stopWatching(id);
        scoreboards.clear(id, OWNER);
        bossBars.clear(id, OWNER);
        mutators.remove(player);
        parties.leave(id);
        votes.forget(id);
        lobby.leave(player);
        messages.send(player, "ctf.join.left");
    }

    /** Somebody left the server. */
    public void quit(UUID player) {
        removeFromRound(player, false);
        spectators.forget(player);
        parties.forget(player);
        votes.forget(player);
        lobby.forget(player);
        wishes.remove(player);
        taking.remove(player);
        lastHurt.remove(player);
        helpers.remove(player);
        marked.remove(player);
        nagged.remove(player);
        streaks.forget(player);
        roles.forget(player);
        progress.forget(player);
        scoreboards.clear(player, OWNER);
        bossBars.clear(player, OWNER);
    }

    public boolean isIn(UUID player) {
        Round now = round;
        if (now == null) {
            return false;
        }
        synchronized (now) {
            return now.has(player);
        }
    }

    /** A side somebody would like; null for none. */
    public void wish(UUID player, TeamColour colour) {
        if (colour == null) {
            wishes.remove(player);
        } else {
            wishes.put(player, colour);
        }
    }

    public Optional<TeamColour> wishOf(UUID player) {
        return Optional.ofNullable(wishes.get(player));
    }

    /** The class for somebody's next life (or the next round). */
    public void pick(Player player, Role role) {
        picks.put(player.getUniqueId(), role);
        progress.pick(player.getUniqueId(), profile -> new de.raindancer.modules.ctf.store.ProfileStore.Profile(
                profile.trail(), profile.show(), role));
        Round now = round;
        if (now != null) {
            synchronized (now) {
                now.fighter(player.getUniqueId()).ifPresent(fighter -> fighter.pickRole(role));
            }
        }
    }

    public Role pickOf(UUID player) {
        Role picked = picks.get(player);
        if (picked == null) {
            picked = progress.profile(player).lastClass();
        }
        return picked == null || !settings.classes() ? settings.defaultClass() : picked;
    }

    /** Staff: start now, whoever is there (two at least). */
    public boolean startNow() {
        if (round != null) {
            return false;
        }
        startRequested = true;
        if (gathering != Phase.COUNTDOWN) {
            openBallot();
        }
        countdown = Math.min(countdown, 3);
        return true;
    }

    /** Staff: the running round ends here, a draw. */
    public boolean stopNow() {
        Round now = round;
        if (now == null || now.phase() == Phase.OVER) {
            return false;
        }
        end(now, Optional.empty(), "ctf.round.stopped");
        return true;
    }

    private List<UUID> lobbyPlayers() {
        List<UUID> here = new ArrayList<>();
        for (UUID id : lobby.everybody()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !spectators.isWatching(id) && !isIn(id)) {
                here.add(id);
            }
        }
        return here;
    }

    /** The maps a round could be played on now. */
    public List<CtfMap> playableMaps() {
        return maps.all().stream().filter(CtfMap::isPlayable).toList();
    }

    private void openBallot() {
        List<String> mapIds = votes.rule().ballot(playableMaps().stream().map(CtfMap::id).toList(), 3, lastMap,
                random);
        List<String> types = new ArrayList<>();
        for (GameType type : GameType.values()) {
            types.add(type.name());
        }
        List<String> twists = new ArrayList<>();
        twists.add(Mutator.NONE.name());
        List<Mutator> allowed = new ArrayList<>(settings.allowedMutators());
        java.util.Collections.shuffle(allowed, random);
        allowed.stream().limit(3).map(Mutator::name).forEach(twists::add);
        votes.open(mapIds, types, twists);
        gathering = Phase.COUNTDOWN;
        countdown = settings.countdownSeconds();
        for (UUID id : lobby.everybody()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                messages.send(player, "ctf.lobby.vote-open");
            }
        }
    }

    // ================================================================== the clocks

    /** Once a second, on the global thread. */
    public void tick() {
        Round now = round;
        long time = clock.getAsLong();
        if (now == null) {
            tickLobby();
            return;
        }
        if (now.phase() == Phase.PREPARING) {
            return;
        }
        if (now.phase() == Phase.OVER) {
            if (time >= overAt) {
                finish(now);
            }
            return;
        }
        synchronized (now) {
            tickRound(now, time);
        }
        board(now, time);
    }

    private void tickLobby() {
        List<UUID> waiting = lobbyPlayers();
        int enough = settings.minPlayers();
        if (gathering == Phase.WAITING) {
            if ((settings.autoStart() || startRequested) && waiting.size() >= enough && !playableMaps().isEmpty()) {
                openBallot();
            }
        } else if (gathering == Phase.COUNTDOWN) {
            if (waiting.size() < Math.min(enough, 2) || (!startRequested && waiting.size() < enough)) {
                gathering = Phase.WAITING;
                votes.close();
                tellLobby("ctf.lobby.not-enough");
            } else {
                if (waiting.size() >= settings.maxPlayersClamped()) {
                    countdown = Math.min(countdown, settings.fullCountdownSeconds());
                }
                countdown--;
                if (countdown <= 5 && countdown > 0) {
                    effects.playForAll(waiting, AnnouncerService.TICK);
                }
                if (countdown <= 0) {
                    startRequested = false;
                    votes.close();
                    start(waiting);
                }
            }
        }
        lobbyBoard(waiting);
    }

    private void tellLobby(String key, Object... values) {
        for (UUID id : lobby.everybody()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                messages.send(player, key, values);
            }
        }
    }

    private void lobbyBoard(List<UUID> waiting) {
        List<Component> lines = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        lines.add(mini.deserialize("<gray>Players: <white>" + waiting.size() + "<gray>/" + settings.maxPlayersClamped()));
        if (gathering == Phase.COUNTDOWN) {
            lines.add(mini.deserialize("<gray>Starts in: <yellow>" + Math.max(0, countdown) + "s"));
        } else {
            lines.add(mini.deserialize("<gray>Waiting for <white>" + Math.max(0, settings.minPlayers() - waiting.size())
                    + "<gray> more"));
        }
        lines.add(Component.empty());
        for (VoteService.Question question : VoteService.Question.values()) {
            votes.winner(question).filter(any -> votes.isOpen()).ifPresent(leading -> lines.add(mini.deserialize(
                    "<gray>" + question.name().charAt(0) + question.name().substring(1).toLowerCase(Locale.ROOT)
                            + ": <aqua>" + label(question, leading))));
        }
        lines.add(Component.empty());
        lines.add(mini.deserialize("<dark_gray>/ctf · right click the banner"));
        Sidebar sidebar = Sidebar.of(mini.deserialize("<red><bold>⚑ CAPTURE THE FLAG</bold>"), lines);
        for (UUID id : lobby.everybody()) {
            if (!isIn(id)) {
                scoreboards.show(id, OWNER, sidebar, ScoreboardPriority.NORMAL);
            }
        }
    }

    public String label(VoteService.Question question, String option) {
        return switch (question) {
            case MAP -> maps.load(option).map(CtfMap::title).orElse(option);
            case TYPE -> GameType.parse(option).map(GameType::title).orElse(option);
            case MUTATOR -> Mutator.parse(option).map(Mutator::title).orElse(option);
        };
    }

    private void tickRound(Round now, long time) {
        if (now.phase() == Phase.RUNNING || now.phase() == Phase.OVERTIME) {
            if (time >= now.endsAt()) {
                timeUp(now, time);
                if (now.phase() == Phase.OVER) {
                    return;
                }
            }
            if (now.type() == GameType.ONE_FLAG && now.half() == 1
                    && time >= now.startedAt() + settings.timeLimit() * 30_000L) {
                halfTime(now, time);
            }
            if (now.mutator() == Mutator.SWAP_BASES && time >= now.nextSwap()) {
                swapBases(now, time);
            }
        }
        respawns(now, time);
        for (Flag flag : now.flags()) {
            if (!flying.contains(flag.side()) && flagRule.returnDue(flag, time, settings.returnSeconds())) {
                flag.sendHome(time);
                flags.show(now, flag);
                announcer.tell(audience(now), "ctf.flag.returned-alone", AnnouncerService.RETURNED,
                        "flag", Markup.of(FlagService.nameOf(now, flag)));
            }
        }
        heat(now, time);
        controlPoint(now);
        compasses(now);
        medics(now);
        devices(now, time);
        for (UUID ninja : roles.vanishOver()) {
            Player player = Bukkit.getPlayer(ninja);
            Optional<Fighter> fighter = now.fighter(ninja);
            if (player != null && fighter.isPresent()) {
                TeamColour colour = now.side(fighter.get().side()).colour();
                Scheduling.entity(plugin, player, () -> roles.reveal(player, colour));
            } else {
                roles.forget(ninja);
            }
        }
        supplies.tick(now, time).ifPresent(at -> announcer.call(audience(now), "ctf.announce.supply", null,
                Cues.NOTIFY));
        marked.entrySet().removeIf(entry -> {
            if (entry.getValue() > time) {
                return false;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && now.carriedBy(entry.getKey()).isEmpty()) {
                Scheduling.entity(plugin, player, () -> player.setGlowing(false));
            }
            return true;
        });
        spectators.follow(now.flags().stream().map(Flag::carrier).flatMap(Optional::stream).findFirst());
    }

    /** Five times a second, on the global thread: each player looked at on their own thread. */
    public void fastTick() {
        Round now = round;
        if (now == null || !now.phase().isPlaying()) {
            return;
        }
        List<Fighter> fighters;
        synchronized (now) {
            fighters = List.copyOf(now.fighters());
        }
        for (Fighter fighter : fighters) {
            Player player = Bukkit.getPlayer(fighter.id());
            if (player != null && fighter.isAlive()) {
                Scheduling.entity(plugin, player, () -> position(now, player));
            }
        }
    }

    private void position(Round now, Player player) {
        if (round != now || isFrozen()) {
            return;
        }
        Location at = player.getLocation();
        if (!at.getWorld().getName().equals(now.world())) {
            return;
        }
        if (at.getY() < now.floorY()) {
            fellOff(player);
            return;
        }
        Point here = new Point(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        long time = clock.getAsLong();
        synchronized (now) {
            Optional<Fighter> found = now.fighter(player.getUniqueId());
            if (found.isEmpty() || !found.get().isAlive() || !now.phase().isPlaying()) {
                return;
            }
            Fighter fighter = found.get();
            Optional<Flag> carrying = now.carriedBy(fighter.id());
            if (carrying.isPresent()) {
                if (flagRule.mayCapture(now, fighter, here, settings.captureRadius(), settings.ownFlagHome())) {
                    capture(now, fighter, player, carrying.get(), time);
                    return;
                }
                if (settings.trail()) {
                    flags.trail(player, progress.profile(fighter.id()).trail(), now.side(fighter.side()).colour());
                }
                if (flagRule.goal(now, fighter.side(), carrying.get()).isPresent() && settings.ownFlagHome()
                        && now.type().everySideHasAFlag() && !now.isSuddenDeath()
                        && now.flagOf(fighter.side()).map(own -> own.state() != Flag.State.HOME).orElse(false)) {
                    Point goal = flagRule.goal(now, fighter.side(), carrying.get()).get();
                    if (here.flatDistanceSquared(goal.x(), goal.z()) <= 9) {
                        nag(player, "ctf.flag.own-flag-away");
                    }
                }
                return;
            }
            touching(now, fighter, player, here, time);
        }
    }

    private void touching(Round now, Fighter fighter, Player player, Point here, long time) {
        Taking slow = taking.get(fighter.id());
        boolean stillAtIt = false;
        for (Flag flag : now.flags()) {
            if (flying.contains(flag.side()) || flag.state() == Flag.State.CARRIED) {
                continue;
            }
            Point where = flag.where();
            if (here.distanceSquared(where.x() + (flag.state() == Flag.State.HOME ? 0.5 : 0), where.y(),
                    where.z() + (flag.state() == Flag.State.HOME ? 0.5 : 0)) > TOUCH * TOUCH * 2.2) {
                continue;
            }
            FlagRule.Touch touch = flagRule.touch(now, fighter, flag);
            switch (touch) {
                case RETURN -> returned(now, fighter, player, flag, time);
                case MAY_NOT_CARRY -> nag(player, "ctf.flag.may-not-carry");
                case TAKE -> {
                    UUID thrower = threwLast.get(flag.side());
                    if (thrower != null && thrower.equals(fighter.id()) && time - flag.since() < 1500) {
                        continue;
                    }
                    long needed = flagRule.pickupMillis(fighter.role(), settings.pickupSeconds(),
                            flag.state() == Flag.State.HOME);
                    if (needed > 0) {
                        if (slow == null || slow.side() != flag.side()) {
                            taking.put(fighter.id(), new Taking(flag.side(), time));
                            stillAtIt = true;
                            continue;
                        }
                        stillAtIt = true;
                        long held = time - slow.since();
                        if (held < needed) {
                            actionBars.show(fighter.id(), OWNER, MiniMessage.miniMessage().deserialize(
                                    "<yellow>Taking the flag… " + bar((double) held / needed)),
                                    Duration.ofMillis(400), ActionBarPriority.HIGH);
                            continue;
                        }
                    }
                    taking.remove(fighter.id());
                    take(now, fighter, player, flag, time);
                    return;
                }
                case NOTHING -> {
                }
            }
        }
        if (!stillAtIt) {
            taking.remove(fighter.id());
        }
    }

    private static String bar(double done) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, done)) * 10);
        return "<green>" + "■".repeat(filled) + "<dark_gray>" + "■".repeat(10 - filled);
    }

    private void nag(Player player, String key) {
        long time = clock.getAsLong();
        Long last = nagged.get(player.getUniqueId());
        if (last != null && time - last < 3000) {
            return;
        }
        nagged.put(player.getUniqueId(), time);
        actionBars.show(player.getUniqueId(), OWNER, messages.get(key), Duration.ofSeconds(2), ActionBarPriority.HIGH);
    }

    // ================================================================== flags

    private void take(Round now, Fighter fighter, Player player, Flag flag, long time) {
        boolean fromHome = flag.state() == Flag.State.HOME;
        flag.take(fighter.id(), time);
        fighter.pickedUp(time);
        if (!flag.isNeutral()) {
            now.flagLeftHome(flag.side());
        }
        threwLast.remove(flag.side());
        if (fromHome) {
            flags.hide(now, flag);
        } else {
            flags.show(now, flag);
        }
        ItemStack helmet = player.getInventory().getItem(EquipmentSlot.HEAD);
        helmets.put(fighter.id(), helmet == null || flags.isFlagItem(helmet) ? new ItemStack(Material.AIR) : helmet.clone());
        roles.reveal(player, now.side(fighter.side()).colour());
        flags.carry(player, now, flag);
        String name = FlagService.nameOf(now, flag);
        announcer.tell(audience(now), "ctf.flag.taken", AnnouncerService.TAKEN, "player", tagged(now, fighter),
                "flag", Markup.of(name));
        if (!flag.isNeutral()) {
            announcer.call(sideAudience(now, flag.side()), "ctf.announce.our-flag-taken", null, null);
        }
        announcer.call(List.of(fighter.id()), "ctf.announce.you-have-it", "ctf.announce.bring-it-home", null);
    }

    private void returned(Round now, Fighter fighter, Player player, Flag flag, long time) {
        flag.sendHome(time);
        fighter.returned(false);
        flags.show(now, flag);
        announcer.tell(audience(now), "ctf.flag.returned", AnnouncerService.RETURNED, "player", tagged(now, fighter),
                "flag", Markup.of(FlagService.nameOf(now, flag)));
        progress.deed(fighter.id(), fighter.name(), ProgressService.Deed.RETURN, fighter.role(), 1);
        progress.pay(fighter.id(), settings.payReturn(), "Capture the Flag: a flag returned");
        if (fighter.returns() >= 5) {
            progress.award(fighter.id(), ProgressService.WALL);
        }
    }

    private void capture(Round now, Fighter fighter, Player player, Flag flag, long time) {
        long took = fighter.carryingFor(time);
        fighter.stoppedCarrying(time, false);
        fighter.captured(took);
        int side = fighter.side();
        int before = now.score(side);
        int[] scoresBefore = now.scores();
        now.scored(side);
        flag.sendHome(time);
        flags.uncarry(player, helmets.remove(fighter.id()));
        flags.show(now, flag);
        Side scoring = now.side(side);
        announcer.tell(audience(now), "ctf.flag.captured", AnnouncerService.CAPTURED, "player", tagged(now, fighter),
                "side", Markup.of(scoring.tagged()), "score", Markup.of(scoreLine(now)));
        announcer.call(audience(now), "ctf.announce.captured", "ctf.announce.captured-by", null,
                "side", Markup.of(scoring.tagged()), "player", fighter.name());
        announcer.captureShow(player.getLocation(), scoring.colour().armourColour(), progress.profile(fighter.id()).show());
        int bestOther = 0;
        for (int other = 0; other < scoresBefore.length; other++) {
            if (other != side) {
                bestOther = Math.max(bestOther, scoresBefore[other]);
            }
        }
        if (bestOther - before >= 2 && now.score(side) >= bestOther) {
            announcer.call(audience(now), "ctf.announce.comeback", null, AnnouncerService.STREAK, "side",
                    scoring.tagged());
        }
        progress.deed(fighter.id(), fighter.name(), ProgressService.Deed.CAPTURE, fighter.role(), 1);
        progress.pay(fighter.id(), settings.payCapture(), "Capture the Flag: a capture");
        progress.award(fighter.id(), ProgressService.FIRST_CAPTURE);
        if (took < 20_000) {
            progress.award(fighter.id(), ProgressService.SPEEDY);
        }
        if (fighter.captures() >= 3) {
            progress.award(fighter.id(), ProgressService.HAT_TRICK);
        }
        Optional<Integer> winner = winRule.afterCapture(now.scores(), settings.capturesToWin(),
                now.phase() == Phase.OVERTIME);
        winner.ifPresent(won -> Scheduling.global(plugin, () -> end(now, Optional.of(won), "ctf.round.won-captures")));
    }

    /** A carrier throws the flag they hold. Their own thread. */
    public void throwFlag(Player player) {
        Round now = round;
        if (now == null || !settings.throwing()) {
            return;
        }
        Flag flag;
        synchronized (now) {
            Optional<Fighter> fighter = now.fighter(player.getUniqueId());
            Optional<Flag> carried = now.carriedBy(player.getUniqueId());
            if (fighter.isEmpty() || carried.isEmpty() || !now.phase().isPlaying()) {
                return;
            }
            flag = carried.get();
            long time = clock.getAsLong();
            fighter.get().stoppedCarrying(time, false);
            flag.drop(flag.home(), time);
            flying.add(flag.side());
            threwLast.put(flag.side(), player.getUniqueId());
        }
        flags.uncarry(player, helmets.remove(player.getUniqueId()));
        effects.play(player.getUniqueId(), AnnouncerService.DROPPED);
        flags.toss(player, now, flag, 1.25, landed -> {
            synchronized (now) {
                flying.remove(flag.side());
                if (round != now || flag.state() != Flag.State.DROPPED) {
                    return;
                }
                long time = clock.getAsLong();
                if (landed == null) {
                    flag.sendHome(time);
                    announcer.tell(audience(now), "ctf.flag.returned-alone", AnnouncerService.RETURNED,
                            "flag", Markup.of(FlagService.nameOf(now, flag)));
                } else {
                    flag.drop(landed, time);
                }
                flags.show(now, flag);
            }
        });
    }

    // ================================================================== falling and coming back

    /** Somebody would have been killed. Their own thread; the hit is already cancelled. */
    public void killed(Player victim, UUID killerId) {
        Round now = round;
        if (now == null) {
            return;
        }
        long time = clock.getAsLong();
        UUID killer = killerId;
        if (killer == null) {
            Hurt hurt = lastHurt.get(victim.getUniqueId());
            if (hurt != null && time - hurt.at() < 10_000) {
                killer = hurt.by();
            }
        }
        Location at = victim.getLocation();
        Point fell = new Point(at.getX(), at.getY(), at.getZ(), 0f, 0f);
        boolean fellOffTheMap = at.getY() < now.floorY();
        synchronized (now) {
            Optional<Fighter> found = now.fighter(victim.getUniqueId());
            if (found.isEmpty() || !found.get().isAlive() || !now.phase().isPlaying()) {
                return;
            }
            Fighter fighter = found.get();
            int respawn = respawnSeconds(now, fighter.side());
            fighter.died(time + respawn * 1000L);
            taking.remove(fighter.id());
            Optional<Flag> carried = now.carriedBy(fighter.id());
            Optional<Fighter> by = killer == null ? Optional.empty() : now.fighter(killer);
            if (carried.isPresent()) {
                Flag flag = carried.get();
                fighter.stoppedCarrying(time, true);
                flags.uncarry(victim, helmets.remove(fighter.id()));
                if (fellOffTheMap) {
                    flag.sendHome(time);
                } else {
                    flag.drop(fell, time);
                }
                flags.show(now, flag);
                announcer.tell(audience(now), fellOffTheMap ? "ctf.flag.lost-to-the-void" : "ctf.flag.dropped",
                        AnnouncerService.DROPPED, "player", tagged(now, fighter), "flag", Markup.of(FlagService.nameOf(now, flag)));
                if (by.isPresent()) {
                    by.get().carrierKill();
                    progress.deed(by.get().id(), by.get().name(), ProgressService.Deed.CARRIER_KILL, by.get().role(), 1);
                    Optional<Point> goal = flagRule.goal(now, fighter.side(), flag);
                    if (goal.isPresent() && fell.flatDistanceSquared(goal.get().x(), goal.get().z())
                            <= (double) settings.clutchRadius() * settings.clutchRadius()) {
                        by.get().returned(true);
                        announcer.call(audience(now), "ctf.announce.denied", "ctf.announce.by",
                                AnnouncerService.DENIED, "player", by.get().name());
                        progress.award(by.get().id(), ProgressService.CLUTCH);
                    }
                }
            }
            if (by.isPresent() && !by.get().id().equals(fighter.id())) {
                credit(now, by.get(), fighter, fell);
            } else {
                streaks.died(fighter.id());
            }
            for (UUID helper : helpers.getOrDefault(fighter.id(), Map.of()).keySet()) {
                if (killer == null || !helper.equals(killer)) {
                    now.fighter(helper).filter(other -> other.side() != fighter.side()).ifPresent(Fighter::assist);
                }
            }
            helpers.remove(fighter.id());
            lastHurt.remove(fighter.id());
            String byName = by.map(Fighter::name).orElse(null);
            String key = byName == null ? (fellOffTheMap ? "ctf.death.fell" : "ctf.death.died") : "ctf.death.killed";
            announcer.tell(audience(now), key, null, "player", tagged(now, fighter),
                    "killer", by.map(other -> (Object) tagged(now, other)).orElse(""));
            deathScreen(victim, now, respawn, at);
        }
    }

    private void credit(Round now, Fighter killer, Fighter victim, Point fell) {
        killer.killed();
        Streaks.Kill kill = streaks.killed(killer.id(), victim.id());
        killer.streak(kill.streak());
        if (kill.streak() >= 7) {
            progress.award(killer.id(), ProgressService.UNSTOPPABLE);
        }
        announcer.callouts(audience(now), kill, killer.name());
        progress.deed(killer.id(), killer.name(), ProgressService.Deed.KILL, killer.role(), 1);
        progress.pay(killer.id(), settings.payKill(), "Capture the Flag: a kill");
        Player killerPlayer = Bukkit.getPlayer(killer.id());
        Point killerAt = killerPlayer == null ? fell : new Point(killerPlayer.getLocation().getX(),
                killerPlayer.getLocation().getY(), killerPlayer.getLocation().getZ(), 0f, 0f);
        for (Flag flag : now.flags()) {
            Optional<UUID> carrier = flag.carrier();
            if (carrier.isPresent() && !carrier.get().equals(killer.id())
                    && now.fighter(carrier.get()).map(mate -> mate.side() == killer.side()).orElse(false)) {
                Player mate = Bukkit.getPlayer(carrier.get());
                if (mate != null && mate.getLocation().distanceSquared(new Location(mate.getWorld(), killerAt.x(),
                        killerAt.y(), killerAt.z())) <= ESCORT * ESCORT) {
                    killer.escortKill();
                    progress.deed(killer.id(), killer.name(), ProgressService.Deed.ESCORT, killer.role(), 1);
                    break;
                }
            }
        }
        now.flagOf(killer.side()).filter(own -> !own.isNeutral()).ifPresent(own -> {
            if (fell.distanceSquared(own.home()) <= DEFENCE * DEFENCE) {
                killer.defenceKill();
            }
        });
    }

    private void deathScreen(Player victim, Round now, int respawnSeconds, Location at) {
        victim.getInventory().clear();
        victim.setFireTicks(0);
        victim.setGlowing(false);
        victim.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(victim::removePotionEffect);
        var max = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        victim.setHealth(max == null ? 20 : max.getValue());
        victim.setGameMode(GameMode.SPECTATOR);
        if (at.getY() < now.floorY()) {
            victim.teleportAsync(spectateSpot(now));
        }
        actionBars.countdown(victim.getUniqueId(), OWNER, Duration.ofSeconds(Math.max(1, respawnSeconds)),
                ActionBarPriority.HIGH, left -> messages.get("ctf.death.back-in", "seconds", (left + 999) / 1000));
        effects.play(victim.getUniqueId(), Cues.HURT);
    }

    private int respawnSeconds(Round now, int side) {
        int seconds = settings.respawnSeconds();
        if (now.mutator() == Mutator.ONE_HIT) {
            seconds = Math.min(seconds, 2);
        }
        if (settings.controlPoint() && now.controlOwner() == side) {
            seconds = Math.max(0, seconds - settings.controlBonus());
        }
        return seconds;
    }

    /** Fell below the map. Their own thread. */
    public void fellOff(Player player) {
        killed(player, null);
    }

    /** Remembers who hurt whom, for credit when somebody falls off the map afterwards. */
    public void hurt(UUID victim, UUID by) {
        if (victim.equals(by)) {
            return;
        }
        long time = clock.getAsLong();
        lastHurt.put(victim, new Hurt(by, time));
        helpers.computeIfAbsent(victim, id -> new ConcurrentHashMap<>()).put(by, time);
    }

    /**
     * The dead whose time is up come back. They count as alive only once they stand at their spawn: alive while the
     * teleport is still on its way, they would pick up the flag lying where they fell.
     */
    private void respawns(Round now, long time) {
        for (Fighter fighter : now.fighters()) {
            if (fighter.isAlive() || fighter.respawnAt() > time || respawning.contains(fighter.id())) {
                continue;
            }
            Player player = Bukkit.getPlayer(fighter.id());
            if (player == null) {
                continue;
            }
            fighter.takeNextRole();
            if (!roles.playable(fighter.role(), settings.building(), now.mutator())) {
                fighter.forceRole(settings.defaultClass());
            }
            respawning.add(fighter.id());
            Location spawn = spawnOf(now, fighter.side());
            Role role = fighter.role();
            TeamColour colour = now.side(fighter.side()).colour();
            Runnable arrived = () -> {
                respawning.remove(fighter.id());
                if (round != now || !now.has(fighter.id())) {
                    return;
                }
                player.setGameMode(gameMode(now));
                roles.equip(player, role, colour, now.mutator());
                mutators.apply(player, now.mutator());
                giveCompass(player, now);
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 4, false, false, true));
                player.setFallDistance(0);
                synchronized (now) {
                    fighter.respawned();
                }
            };
            Scheduling.entity(plugin, player, () -> player.teleportAsync(spawn).thenRun(() ->
                    Scheduling.entity(plugin, player, arrived, () -> respawning.remove(fighter.id()))),
                    () -> respawning.remove(fighter.id()));
        }
    }

    private GameMode gameMode(Round now) {
        return settings.building() != Building.NONE || now.mutator().needsBreaking()
                ? GameMode.SURVIVAL : GameMode.ADVENTURE;
    }

    private Location spawnOf(Round now, int side) {
        Base base = now.baseOf(side);
        Point spot = base.spawns().get(random.nextInt(base.spawns().size()));
        Point at = now.abs(spot);
        World world = Bukkit.getWorld(now.world());
        return new Location(world, at.x(), at.y(), at.z(), at.yaw(), at.pitch());
    }

    private Location spectateSpot(Round now) {
        World world = Bukkit.getWorld(now.world());
        Point spot = now.map().spectate();
        if (spot == null) {
            int x = now.originX() + now.map().width() / 2;
            int z = now.originZ() + now.map().length() / 2;
            return new Location(world, x, now.originY() + now.map().height() + 10, z, 0f, 60f);
        }
        Point at = now.abs(spot);
        return new Location(world, at.x(), at.y(), at.z(), at.yaw(), at.pitch());
    }

    // ================================================================== the once-a-second things

    private void heat(Round now, long time) {
        for (Flag flag : now.flags()) {
            flag.carrier().ifPresent(carrier -> {
                Player player = Bukkit.getPlayer(carrier);
                Optional<Fighter> fighter = now.fighter(carrier);
                if (player == null || fighter.isEmpty()) {
                    return;
                }
                FlagRule.Heat heat = flagRule.heat(fighter.get().carryingFor(time), settings.glowAfter(),
                        settings.slowAfter());
                Scheduling.entity(plugin, player, () -> {
                    if (heat != FlagRule.Heat.COOL) {
                        player.setGlowing(true);
                    }
                    if (heat == FlagRule.Heat.SLOWING) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, false, true));
                    }
                });
            });
        }
    }

    private void controlPoint(Round now) {
        Point control = now.map().control();
        if (!settings.controlPoint() || control == null) {
            return;
        }
        Point at = now.abs(control);
        int[] onPoint = new int[now.sides().size()];
        for (Fighter fighter : now.fighters()) {
            Player player = Bukkit.getPlayer(fighter.id());
            if (player != null && fighter.isAlive() && player.getWorld().getName().equals(now.world())
                    && at.distanceSquared(player.getLocation().getX(), player.getLocation().getY(),
                    player.getLocation().getZ()) <= 9) {
                onPoint[fighter.side()]++;
            }
        }
        ControlRule.State before = new ControlRule.State(now.controlOwner(), now.controlLeaning(), now.controlProgress());
        ControlRule.State after = controlRule.tick(before, onPoint, settings.controlSeconds());
        now.control(after.owner(), after.leaning(), after.progress());
        if (after.owner() != before.owner() && after.owner() >= 0) {
            announcer.call(audience(now), "ctf.announce.control-taken", null, Cues.NOTIFY,
                    "side", Markup.of(now.side(after.owner()).tagged()));
        } else if (after.owner() < 0 && before.owner() >= 0) {
            announcer.tell(audience(now), "ctf.control.lost", null, "side", Markup.of(now.side(before.owner()).tagged()));
        }
        if (after.progress() > 0 && after.progress() < 100 && after.leaning() >= 0) {
            effects.playAt(now.world(), at.x(), at.y() + 0.2, at.z(), de.raindancer.core.ui.effect.Effect.of(
                    new de.raindancer.core.ui.effect.ParticleCue("DUST", 12, 1.2, 0.1, 1.2, 0,
                            now.side(after.leaning()).colour().armourColour().asRGB())));
        }
    }

    private void compasses(Round now) {
        if (!settings.compass()) {
            return;
        }
        World world = Bukkit.getWorld(now.world());
        for (Fighter fighter : now.fighters()) {
            Player player = Bukkit.getPlayer(fighter.id());
            if (player == null || !fighter.isAlive() || world == null) {
                continue;
            }
            Optional<Flag> watched = now.type().everySideHasAFlag() ? now.flagOf(fighter.side())
                    : now.flags().stream().findFirst();
            watched.ifPresent(flag -> {
                Location target = flag.carrier().map(Bukkit::getPlayer).map(Player::getLocation)
                        .orElseGet(() -> new Location(world, flag.where().x(), flag.where().y(), flag.where().z()));
                Scheduling.entity(plugin, player, () -> player.setCompassTarget(target));
            });
        }
    }

    private void giveCompass(Player player, Round now) {
        if (settings.compass()) {
            ItemStack compass = new ItemStack(Material.COMPASS);
            compass.editMeta(meta -> meta.itemName(MiniMessage.miniMessage().deserialize(
                    now.type().everySideHasAFlag() ? "<yellow>Your flag" : "<yellow>The flag")));
            player.getInventory().setItem(7, compass);
        }
    }

    private void medics(Round now) {
        if (clock.getAsLong() / 1000 % 2 != 0) {
            return;
        }
        for (Fighter medic : now.fighters()) {
            if (medic.role() != Role.MEDIC || !medic.isAlive()) {
                continue;
            }
            Player healer = Bukkit.getPlayer(medic.id());
            if (healer == null) {
                continue;
            }
            for (Fighter mate : now.onSide(medic.side())) {
                Player player = Bukkit.getPlayer(mate.id());
                if (player == null || !mate.isAlive() || player.equals(healer)
                        || !player.getWorld().equals(healer.getWorld())
                        || player.getLocation().distanceSquared(healer.getLocation())
                        > RoleService.AURA_RADIUS * RoleService.AURA_RADIUS) {
                    continue;
                }
                Scheduling.entity(plugin, player, () -> {
                    var max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    double full = max == null ? 20 : max.getValue();
                    if (player.getHealth() < full && !player.isDead()) {
                        player.setHealth(Math.min(full, player.getHealth() + RoleService.AURA_HEAL));
                    }
                });
            }
        }
    }

    /** A medic mends a teammate fully, once a life. Medic's own thread. */
    public boolean mend(Player medic, Player target) {
        Round now = round;
        if (now == null) {
            return false;
        }
        synchronized (now) {
            Optional<Fighter> healer = now.fighter(medic.getUniqueId());
            Optional<Fighter> mate = now.fighter(target.getUniqueId());
            if (healer.isEmpty() || mate.isEmpty() || healer.get().role() != Role.MEDIC || healer.get().reviveUsed()
                    || mate.get().side() != healer.get().side() || !mate.get().isAlive()) {
                return false;
            }
            healer.get().useRevive();
        }
        Scheduling.entity(plugin, target, () -> {
            var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            target.setHealth(max == null ? 20 : max.getValue());
            target.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
        });
        effects.play(target.getUniqueId(), Cues.HEAL);
        return true;
    }

    // ================================================================== devices: turrets, webs, alarms

    /** An engineer's turret, a trapper's web or alarm, put down at a block. */
    public void device(String kind, Player owner, Block block, int seconds) {
        Round now = round;
        if (now == null) {
            return;
        }
        synchronized (now) {
            now.fighter(owner.getUniqueId()).ifPresent(fighter -> devices.add(new Device(kind, fighter.side(),
                    owner.getUniqueId(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                    clock.getAsLong() + seconds * 1000L)));
        }
    }

    private void devices(Round now, long time) {
        World world = Bukkit.getWorld(now.world());
        if (world == null) {
            return;
        }
        for (Device device : devices) {
            Location at = new Location(world, device.x() + 0.5, device.y() + 0.5, device.z() + 0.5);
            if (device.until() <= time) {
                devices.remove(device);
                if (!RoleService.ALARM.equals(device.kind())) {
                    Scheduling.region(plugin, at, () -> at.getBlock().setType(Material.AIR, false));
                }
                continue;
            }
            switch (device.kind()) {
                case RoleService.TURRET -> turret(now, device, at);
                case RoleService.ALARM -> alarm(now, device, at);
                default -> {
                }
            }
        }
    }

    private void turret(Round now, Device turret, Location at) {
        Player target = null;
        double best = 14 * 14;
        for (Fighter enemy : now.fighters()) {
            if (enemy.side() == turret.side() || !enemy.isAlive() || roles.isVanished(enemy.id())) {
                continue;
            }
            Player player = Bukkit.getPlayer(enemy.id());
            if (player != null && player.getWorld().equals(at.getWorld())) {
                double distance = player.getLocation().distanceSquared(at);
                if (distance < best) {
                    best = distance;
                    target = player;
                }
            }
        }
        if (target == null) {
            return;
        }
        Player aim = target;
        Player owner = Bukkit.getPlayer(turret.owner());
        Scheduling.region(plugin, at, () -> {
            Vector toward = aim.getEyeLocation().toVector().subtract(at.toVector()).normalize();
            Location muzzle = at.clone().add(toward.clone().multiply(0.8));
            Arrow arrow = at.getWorld().spawnArrow(muzzle, toward, 1.6f, 4f);
            arrow.setPersistent(false);
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            if (owner != null) {
                arrow.setShooter(owner);
            }
        });
    }

    private void alarm(Round now, Device alarm, Location at) {
        for (Fighter enemy : now.fighters()) {
            if (enemy.side() == alarm.side() || !enemy.isAlive()) {
                continue;
            }
            Player player = Bukkit.getPlayer(enemy.id());
            if (player != null && player.getWorld().equals(at.getWorld())
                    && player.getLocation().distanceSquared(at) <= 9) {
                devices.remove(alarm);
                mark(player.getUniqueId(), 4);
                announcer.tell(sideAudience(now, alarm.side()), "ctf.trap.alarm", Cues.WARN,
                        "player", tagged(now, enemy));
                return;
            }
        }
    }

    /** Makes somebody glow for a while — an archer's mark, an alarm. */
    public void mark(UUID player, int seconds) {
        marked.put(player, clock.getAsLong() + seconds * 1000L);
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            Scheduling.entity(plugin, target, () -> target.setGlowing(true));
        }
    }

    // ================================================================== time, halves, swaps

    private void timeUp(Round now, long time) {
        WinRule.Ending ending = winRule.atTimeUp(now.scores(), settings.tieBreak(), now.phase() == Phase.OVERTIME);
        switch (ending.kind()) {
            case WIN -> Scheduling.global(plugin, () -> end(now, Optional.of(ending.side()), "ctf.round.won-time"));
            case DRAW -> Scheduling.global(plugin, () -> end(now, Optional.empty(), "ctf.round.draw"));
            case OVERTIME -> {
                now.phase(Phase.OVERTIME);
                now.endsAt(time + settings.overtime() * 60_000L);
                announcer.call(audience(now), "ctf.announce.overtime", "ctf.announce.next-capture",
                        AnnouncerService.OVERTIME);
            }
            case SUDDEN_DEATH -> {
                now.phase(Phase.OVERTIME);
                now.endsAt(time + settings.overtime() * 60_000L);
                now.suddenDeath();
                Point middle = middleOf(now);
                int count = 0;
                for (Flag flag : now.flags()) {
                    if (flag.state() == Flag.State.CARRIED) {
                        flag.carrier().map(Bukkit::getPlayer).ifPresent(carrier ->
                                Scheduling.entity(plugin, carrier, () -> flags.uncarry(carrier,
                                        helmets.remove(carrier.getUniqueId()))));
                        flag.carrier().flatMap(now::fighter).ifPresent(fighter -> fighter.stoppedCarrying(time, false));
                    }
                    flags.hide(now, flag);
                    flag.moveHome(middle.plus((count++ - (now.flags().size() - 1) / 2.0) * 3, 0, 0), time);
                    flags.show(now, flag);
                }
                announcer.call(audience(now), "ctf.announce.sudden-death", "ctf.announce.sudden-death-how",
                        AnnouncerService.OVERTIME);
            }
        }
    }

    private Point middleOf(Round now) {
        if (now.map().neutral() != null) {
            return now.abs(now.map().neutral());
        }
        double x = 0;
        double y = 0;
        double z = 0;
        List<Base> bases = now.map().completeBases();
        for (Base base : bases) {
            x += base.flag().x();
            y += base.flag().y();
            z += base.flag().z();
        }
        return now.abs(Point.of(x / bases.size(), y / bases.size(), z / bases.size()));
    }

    private void halfTime(Round now, long time) {
        now.secondHalf();
        int attackers = now.defenders();
        int defenders = 1 - now.defenders();
        now.defenders(defenders);
        for (Flag flag : now.flags()) {
            flag.carrier().map(Bukkit::getPlayer).ifPresent(carrier -> Scheduling.entity(plugin, carrier,
                    () -> flags.uncarry(carrier, helmets.remove(carrier.getUniqueId()))));
            flags.hide(now, flag);
        }
        Flag theirs = new Flag(defenders, now.abs(now.baseOf(defenders).flag()), time);
        now.replaceFlags(List.of(theirs));
        flags.show(now, theirs);
        for (Fighter fighter : now.fighters()) {
            fighter.stoppedCarrying(time, false);
            fighter.sendBack(time);
        }
        announcer.call(audience(now), "ctf.announce.half-time", "ctf.announce.swap-sides", AnnouncerService.OVERTIME,
                "side", Markup.of(now.side(attackers).tagged()));
    }

    private void swapBases(Round now, long time) {
        int count = now.sides().size();
        now.nextSwap(time + SWAP_SECONDS * 1000L);
        for (int side = 0; side < count; side++) {
            now.side(side, now.side(side).withBase((now.side(side).base() + 1) % count));
        }
        for (Flag flag : now.flags()) {
            if (flag.isNeutral()) {
                continue;
            }
            Point home = now.abs(now.baseOf(flag.side()).flag());
            if (flag.state() == Flag.State.HOME) {
                flags.hide(now, flag);
                flag.moveHome(home, time);
                flags.show(now, flag);
            } else {
                flag.rehome(home);
            }
        }
        announcer.call(audience(now), "ctf.announce.swap", "ctf.announce.swap-how", AnnouncerService.OVERTIME);
    }

    // ================================================================== starting

    private void start(List<UUID> waiting) {
        List<CtfMap> playable = playableMaps();
        if (playable.isEmpty()) {
            gathering = Phase.WAITING;
            tellLobby("ctf.lobby.no-maps");
            return;
        }
        CtfMap map = votes.winner(VoteService.Question.MAP).flatMap(maps::load).filter(CtfMap::isPlayable)
                .orElseGet(() -> playable.get(random.nextInt(playable.size())));
        GameType type = votes.winner(VoteService.Question.TYPE).flatMap(GameType::parse).orElse(settings.defaultType());
        if (!map.supports(type)) {
            type = GameType.CLASSIC;
        }
        Mutator mutator = votes.winner(VoteService.Question.MUTATOR).flatMap(Mutator::parse).orElseGet(() -> {
            List<Mutator> allowed = settings.allowedMutators();
            return !allowed.isEmpty() && random.nextInt(100) < settings.mutatorChance()
                    ? allowed.get(random.nextInt(allowed.size())) : Mutator.NONE;
        });
        if (!settings.allowedMutators().contains(mutator)) {
            mutator = Mutator.NONE;
        }
        GameType chosenType = type;
        Mutator chosenMutator = mutator;
        gathering = Phase.PREPARING;
        lastMap = map.id();
        Optional<ArenaWorldService.Built> built = arena.builtNow(map);
        if (built.isPresent()) {
            begin(map, chosenType, chosenMutator, built.get(), waiting);
            return;
        }
        tellLobby("ctf.lobby.building", "map", map.title());
        arena.build(map, where -> begin(map, chosenType, chosenMutator, where, lobbyPlayers()), failure -> {
            log.warn("The map {} could not be built ({}); the round does not start.", map.id(), failure);
            gathering = Phase.WAITING;
            tellLobby("ctf.lobby.build-failed", "map", map.title());
        });
    }

    private void begin(CtfMap map, GameType wanted, Mutator mutator, ArenaWorldService.Built where,
                       List<UUID> candidates) {
        long time = clock.getAsLong();
        List<UUID> players = new ArrayList<>(candidates.stream().filter(id -> Bukkit.getPlayer(id) != null)
                .limit(settings.maxPlayersClamped()).toList());
        GameType type = wanted;
        Map<UUID, Integer> assigned;
        List<Side> sides = new ArrayList<>();
        List<Base> bases = map.completeBases();
        Optional<BalanceRule.GuildSides> guilds = Optional.empty();
        if (type == GameType.GUILD_WAR) {
            Map<UUID, String> groupOf = new HashMap<>();
            for (UUID player : players) {
                Groups.of(player).stream().findFirst().ifPresent(group -> groupOf.put(player, group.id()));
            }
            guilds = balance.guildSides(players, groupOf);
            if (guilds.isEmpty()) {
                tellLobby("ctf.lobby.no-guild-war");
                type = GameType.CLASSIC;
            }
        }
        int sideCount = type == GameType.CLASSIC ? Math.min(settings.sidesClamped(), bases.size()) : 2;
        for (int index = 0; index < sideCount; index++) {
            TeamColour colour = bases.get(index).colour();
            String group = "";
            String name = colourName(colour);
            if (guilds.isPresent() && type == GameType.GUILD_WAR) {
                group = guilds.get().groups().get(index);
                name = Groups.group(group).map(Groups.Group::name).orElse(name);
            }
            sides.add(new Side(index, colour, name, index, group));
        }
        if (guilds.isPresent() && type == GameType.GUILD_WAR) {
            assigned = guilds.get().sides();
        } else {
            Map<UUID, Integer> wished = new HashMap<>();
            for (UUID player : players) {
                TeamColour colour = wishes.get(player);
                for (Side side : sides) {
                    if (side.colour() == colour) {
                        wished.put(player, side.index());
                    }
                }
            }
            assigned = balance.assign(players, sideCount, wished, parties.all(), random);
        }
        Round fresh = new Round(map, type, mutator, arena.worldName(), where.originX(), where.originY(),
                where.originZ(), sides);
        if (type == GameType.NEUTRAL) {
            fresh.addFlag(new Flag(Flag.NEUTRAL, fresh.abs(map.neutral()), time));
        } else if (type == GameType.ONE_FLAG) {
            fresh.defenders(1);
            fresh.addFlag(new Flag(1, fresh.abs(bases.get(1).flag()), time));
        } else {
            for (Side side : sides) {
                fresh.addFlag(new Flag(side.index(), fresh.abs(bases.get(side.base()).flag()), time));
            }
        }
        for (Map.Entry<UUID, Integer> entry : assigned.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            if (!progress.charge(entry.getKey(), settings.entryFee())) {
                messages.send(player, "ctf.lobby.cannot-pay", "fee", progress.format(settings.entryFee()));
                continue;
            }
            if (settings.entryFee() > 0) {
                fees.put(entry.getKey(), settings.entryFee());
            }
            Role role = pickOf(entry.getKey());
            Fighter fighter = new Fighter(entry.getKey(), player.getName(), entry.getValue(), role, time);
            if (!roles.playable(role, settings.building(), mutator)) {
                fighter.forceRole(settings.defaultClass());
            }
            fresh.add(fighter);
        }
        if (fresh.fighters().size() < 2) {
            gathering = Phase.WAITING;
            tellLobby("ctf.lobby.not-enough");
            fees.forEach(progress::refund);
            fees.clear();
            return;
        }
        streaks.reset();
        placed.clear();
        flying.clear();
        threwLast.clear();
        devices.clear();
        taking.clear();
        rewind.arm();
        round = fresh;
        gathering = Phase.WAITING;
        frozenUntil = time + FREEZE_SECONDS * 1000L;
        fresh.clock(frozenUntil, frozenUntil + settings.timeLimit() * 60_000L);
        fresh.nextSwap(frozenUntil + SWAP_SECONDS * 1000L);
        fresh.phase(Phase.RUNNING);
        for (Flag flag : fresh.flags()) {
            flags.show(fresh, flag);
        }
        powerUps.place(fresh);
        supplies.start(frozenUntil);
        String typeLine = type.title() + (mutator == Mutator.NONE ? "" : " · " + mutator.title());
        for (Fighter fighter : fresh.fighters()) {
            Player player = Bukkit.getPlayer(fighter.id());
            if (player == null) {
                continue;
            }
            Location spawn = spawnOf(fresh, fighter.side());
            Side side = fresh.side(fighter.side());
            Role role = fighter.role();
            scoreboards.clear(fighter.id(), OWNER);
            Scheduling.entity(plugin, player, () -> player.teleportAsync(spawn).thenRun(() ->
                    Scheduling.entity(plugin, player, () -> {
                        player.setGameMode(gameMode(fresh));
                        roles.equip(player, role, side.colour(), mutator);
                        mutators.apply(player, mutator);
                        giveCompass(player, fresh);
                        messages.send(player, "ctf.round.started", "map", map.title(), "author",
                                map.author().isBlank() ? "" : " by " + map.author(), "type", typeLine,
                                "side", Markup.of(side.tagged()), "goal", settings.capturesToWin());
                        if (mutator != Mutator.NONE) {
                            messages.send(player, "ctf.round.twist", "twist", mutator.title(),
                                    "how", String.join(" ", mutator.lines()));
                        }
                    })));
        }
        if (settings.teamVoice()) {
            for (Side side : fresh.sides()) {
                VoiceGroups.place("ctf:" + side.index(), side.name(), fresh.onSide(side.index()).stream()
                        .map(Fighter::id).toList());
            }
        }
        for (int second = FREEZE_SECONDS; second > 0; second--) {
            int left = second;
            Scheduling.globalLater(plugin, (FREEZE_SECONDS - second) * 20L, () -> {
                if (round == fresh) {
                    announcer.call(audience(fresh), "ctf.announce.countdown", null, AnnouncerService.TICK,
                            "seconds", left);
                }
            });
        }
        Scheduling.globalLater(plugin, FREEZE_SECONDS * 20L, () -> {
            if (round == fresh) {
                announcer.call(audience(fresh), "ctf.announce.go", "ctf.announce.go-how", AnnouncerService.GO);
            }
        });
        for (UUID watcher : lobby.everybody()) {
            if (!fresh.has(watcher)) {
                Player player = Bukkit.getPlayer(watcher);
                if (player != null) {
                    Scheduling.entity(plugin, player, () -> watch(player));
                }
            }
        }
        log.info("A round started on {}: {}, {}, {} player(s) on {} side(s).", map.id(), type, mutator,
                fresh.fighters().size(), sides.size());
    }

    private static String colourName(TeamColour colour) {
        String name = colour.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Somebody coming in while a round runs: the smaller side. Global thread. */
    public void joinLate(Player player) {
        Round now = round;
        if (now == null || !now.phase().isPlaying()) {
            return;
        }
        synchronized (now) {
            if (now.has(player.getUniqueId())) {
                return;
            }
            if (now.fighters().size() >= settings.maxPlayersClamped() || now.type() == GameType.GUILD_WAR) {
                watch(player);
                return;
            }
            if (!progress.charge(player.getUniqueId(), settings.entryFee())) {
                messages.send(player, "ctf.lobby.cannot-pay", "fee", progress.format(settings.entryFee()));
                watch(player);
                return;
            }
            if (settings.entryFee() > 0) {
                fees.put(player.getUniqueId(), settings.entryFee());
            }
            int[] sizes = new int[now.sides().size()];
            now.fighters().forEach(fighter -> sizes[fighter.side()]++);
            Integer wish = wishes.containsKey(player.getUniqueId()) ? now.sides().stream()
                    .filter(side -> side.colour() == wishes.get(player.getUniqueId())).map(Side::index)
                    .findFirst().orElse(null) : null;
            int side = balance.lateSide(sizes, wish);
            Fighter fighter = new Fighter(player.getUniqueId(), player.getName(), side, pickOf(player.getUniqueId()),
                    clock.getAsLong());
            if (!roles.playable(fighter.role(), settings.building(), now.mutator())) {
                fighter.forceRole(settings.defaultClass());
            }
            fighter.sendBack(clock.getAsLong());
            now.add(fighter);
            spectators.stopWatching(player.getUniqueId());
            messages.send(player, "ctf.round.joined-late", "side", Markup.of(now.side(side).tagged()));
            if (settings.teamVoice()) {
                VoiceGroups.place("ctf:" + side, now.side(side).name(), List.of(player.getUniqueId()));
            }
        }
    }

    /** Somebody switches to another side mid-round, if it keeps the sides even. Their own thread. */
    public boolean switchSide(Player player, int to) {
        Round now = round;
        if (now == null || !now.phase().isPlaying() || now.type() == GameType.GUILD_WAR) {
            return false;
        }
        synchronized (now) {
            Optional<Fighter> fighter = now.fighter(player.getUniqueId());
            if (fighter.isEmpty() || to < 0 || to >= now.sides().size()
                    || now.carriedBy(player.getUniqueId()).isPresent()) {
                return false;
            }
            int[] sizes = new int[now.sides().size()];
            now.fighters().forEach(each -> sizes[each.side()]++);
            if (!balance.maySwitch(sizes, fighter.get().side(), to)) {
                return false;
            }
            fighter.get().side(to);
            fighter.get().sendBack(clock.getAsLong());
            player.setGameMode(GameMode.SPECTATOR);
            return true;
        }
    }

    /** Watches the round from above. Their own thread or the global one. */
    public void watch(Player player) {
        Round now = round;
        if (now == null) {
            return;
        }
        Scheduling.entity(plugin, player, () -> {
            spectators.watch(player, spectateSpot(now));
            messages.send(player, "ctf.round.watching");
        });
    }

    private void removeFromRound(UUID player, boolean online) {
        Round now = round;
        taking.remove(player);
        if (now == null) {
            return;
        }
        synchronized (now) {
            Optional<Fighter> fighter = now.fighter(player);
            if (fighter.isEmpty()) {
                return;
            }
            long time = clock.getAsLong();
            now.carriedBy(player).ifPresent(flag -> {
                fighter.get().stoppedCarrying(time, true);
                flag.sendHome(time);
                flags.show(now, flag);
                announcer.tell(audience(now), "ctf.flag.returned-alone", AnnouncerService.RETURNED,
                        "flag", Markup.of(FlagService.nameOf(now, flag)));
            });
            Player bukkit = Bukkit.getPlayer(player);
            if (bukkit != null && online) {
                flags.uncarry(bukkit, helmets.remove(player));
            }
            helmets.remove(player);
            now.remove(player);
            int[] perSide = new int[now.sides().size()];
            now.fighters().forEach(each -> perSide[each.side()]++);
            if (now.phase().isPlaying()) {
                Optional<Integer> standing = winRule.lastSideStanding(perSide);
                boolean anybody = java.util.Arrays.stream(perSide).sum() > 0;
                if (standing.isPresent() || !anybody) {
                    Scheduling.global(plugin, () -> end(now, standing, standing.isPresent()
                            ? "ctf.round.won-forfeit" : "ctf.round.draw"));
                }
            }
        }
    }

    // ================================================================== ending

    /** The round is over: results, awards, rewards; everybody back to the lobby after a while. Global thread. */
    public void end(Round now, Optional<Integer> winner, String reasonKey) {
        if (round != now) {
            return;
        }
        List<Fighter> fighters;
        synchronized (now) {
            if (now.phase() == Phase.OVER) {
                return;
            }
            now.phase(Phase.OVER);
            fighters = List.copyOf(now.fighters());
        }
        overAt = clock.getAsLong() + RESULTS_SECONDS * 1000L;
        bossBars.clearShared(OWNER, "round");
        Map<Award, UUID> awards = settings.awards() ? awardRule.awards(fighters) : Map.of();
        String winnerTag = winner.map(side -> now.side(side).tagged()).orElse("");
        MiniMessage mini = MiniMessage.miniMessage();
        Collection<UUID> everybody = audience(now);
        announcer.tell(everybody, reasonKey, null, "side", Markup.of(winnerTag), "score", Markup.of(scoreLine(now)));
        if (!awards.isEmpty()) {
            announcer.tell(everybody, "ctf.awards.header", null);
            for (Map.Entry<Award, UUID> award : awards.entrySet()) {
                Object name = fighters.stream().filter(fighter -> fighter.id().equals(award.getValue()))
                        .map(fighter -> (Object) tagged(now, fighter)).findFirst().orElse("?");
                announcer.tell(everybody, "ctf.awards.line", null, "award", award.getKey().title(),
                        "player", name, "for", award.getKey().forWhat());
            }
            Optional.ofNullable(awards.get(Award.MVP)).ifPresent(mvp -> progress.award(mvp, ProgressService.MVP));
        }
        List<UUID> winners = new ArrayList<>();
        for (Fighter fighter : fighters) {
            boolean won = winner.map(side -> side == fighter.side()).orElse(false);
            boolean lost = winner.isPresent() && !won;
            if (won) {
                winners.add(fighter.id());
            }
            Player player = Bukkit.getPlayer(fighter.id());
            if (player != null) {
                Component title = mini.deserialize(winner.isEmpty() ? "<yellow>Draw"
                        : won ? "<gold><bold>Victory!" : "<red>Defeat");
                Component subtitle = mini.deserialize(winner.isEmpty() ? scoreLine(now) : winnerTag + " <gray>wins · "
                        + scoreLine(now));
                announcer.finale(player, title, subtitle, won);
            }
            int xp = progress.levelRule().earned(fighter.captures(), fighter.returns(), fighter.kills(),
                    fighter.carrierKills(), won);
            progress.addXp(fighter.id(), fighter.name(), xp, false);
            progress.record(new StatsStore.Delta(fighter.id(), fighter.name(), won, lost, fighter.captures(),
                    fighter.returns(), fighter.kills(), fighter.deaths(), fighter.carrierKills(),
                    fighter.id().equals(awards.get(Award.MVP)), fighter.fastestCapture().orElse(0), xp));
            progress.progressAchievement(fighter.id(), ProgressService.VETERAN, 1);
            if (won) {
                progress.deed(fighter.id(), fighter.name(), ProgressService.Deed.WIN, fighter.role(), 1);
                progress.pay(fighter.id(), settings.payWin(), "Capture the Flag: a round won");
                if (now.type().everySideHasAFlag() && !now.flagEverLeftHome(fighter.side())) {
                    progress.award(fighter.id(), ProgressService.CLEAN_SHEET);
                }
            }
            if (fighter.role() == Role.MEDIC) {
                progress.deed(fighter.id(), fighter.name(), ProgressService.Deed.PLAYED, Role.MEDIC, 1);
            }
        }
        int pot = fees.values().stream().mapToInt(Integer::intValue).sum();
        if (pot > 0) {
            if (winners.isEmpty()) {
                fees.forEach(progress::refund);
            } else {
                progress.sharePot(pot, winners);
            }
        }
        fees.clear();
        for (Side side : now.sides()) {
            VoiceGroups.disband("ctf:" + side.index());
        }
        log.info("A round on {} ended: {} ({}).", now.map().id(), winner.map(side -> now.side(side).name())
                .orElse("no winner"), scoreLine(now));
    }

    /** After the results: the map put back, everybody back in the lobby. Global thread. */
    private void finish(Round now) {
        if (round != now) {
            return;
        }
        round = null;
        flags.clear(now);
        powerUps.clear();
        supplies.stop();
        roles.clear();
        streaks.reset();
        devices.clear();
        marked.clear();
        sweep(now);
        rewind.rewind(blocks -> {
            if (blocks > 0) {
                log.info("The map {} is as it was again: {} block(s) put back.", now.map().id(), blocks);
            }
        });
        Set<UUID> back = new java.util.HashSet<>();
        now.fighters().forEach(fighter -> back.add(fighter.id()));
        back.addAll(spectators.watchers());
        spectators.clear();
        for (UUID id : back) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            scoreboards.clear(id, OWNER);
            Scheduling.entity(plugin, player, () -> {
                mutators.remove(player);
                if (lobby.isInside(id)) {
                    lobby.reset(player);
                    player.teleportAsync(lobby.spawn());
                }
            });
        }
        helmets.clear();
        respawning.clear();
        gathering = Phase.WAITING;
    }

    /** Arrows, dropped things and the like lying on the map go. */
    private void sweep(Round now) {
        World world = Bukkit.getWorld(now.world());
        if (world == null) {
            return;
        }
        int minChunkX = now.originX() >> 4;
        int maxChunkX = (now.originX() + now.map().width()) >> 4;
        int minChunkZ = now.originZ() >> 4;
        int maxChunkZ = (now.originZ() + now.map().length()) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                int chunkX = x;
                int chunkZ = z;
                Scheduling.region(plugin, new Location(world, chunkX * 16 + 8, 64, chunkZ * 16 + 8), () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        return;
                    }
                    for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        if (!(entity instanceof Player) && !(entity instanceof org.bukkit.entity.Display)) {
                            entity.remove();
                        }
                    }
                });
            }
        }
    }

    /** At shutdown: a running round ends without results, the map is put back, everybody is let go. */
    public void shutdown() {
        Round now = round;
        if (now != null) {
            round = null;
            flags.clear(now);
            for (Side side : now.sides()) {
                VoiceGroups.disband("ctf:" + side.index());
            }
            fees.forEach(progress::refund);
            fees.clear();
            rewind.rewind(blocks -> { });
        }
        powerUps.stop();
        supplies.stop();
    }

    // ================================================================== building

    /** Whether the block is inside the running round's map (and a little above it), for the rewind. */
    private boolean insideTheMap(Block block) {
        Round now = round;
        if (now == null || !block.getWorld().getName().equals(now.world())) {
            return false;
        }
        return area(now).contains(block.getX(), block.getY(), block.getZ());
    }

    private static BuildRule.Area area(Round now) {
        return new BuildRule.Area(now.originX() - 8, now.originY() - 8, now.originZ() - 8,
                now.originX() + now.map().width() + 8, now.originY() + now.map().height() + 24,
                now.originZ() + now.map().length() + 8);
    }

    private List<Point> keepClear(Round now) {
        List<Point> points = new ArrayList<>();
        for (Base base : now.map().completeBases()) {
            points.add(now.abs(base.flag()));
            base.spawns().stream().map(now::abs).forEach(points::add);
        }
        now.flags().stream().map(Flag::home).forEach(points::add);
        return points;
    }

    /** Whether somebody may put a block here now. */
    public boolean mayPlace(Player player, Block block) {
        Round now = round;
        if (now == null || !now.phase().isPlaying() || isFrozen()) {
            return false;
        }
        synchronized (now) {
            if (now.fighter(player.getUniqueId()).filter(Fighter::isAlive).isEmpty()) {
                return false;
            }
            Building building = settings.building();
            if (building == Building.NONE) {
                return false;
            }
            return buildRule.mayPlace(building, block.getX(), block.getY(), block.getZ(), area(now), keepClear(now),
                    settings.keepClear());
        }
    }

    /** Whether a trapper may put a web or an alarm here: in the map, not on a flag or a spawn. */
    public boolean mayTrap(Player player, Block block) {
        Round now = round;
        if (now == null || !now.phase().isPlaying() || isFrozen()) {
            return false;
        }
        synchronized (now) {
            if (now.fighter(player.getUniqueId()).filter(Fighter::isAlive).isEmpty()) {
                return false;
            }
            return buildRule.mayPlace(Building.FREE, block.getX(), block.getY(), block.getZ(), area(now),
                    keepClear(now), 2);
        }
    }

    public void placedBlock(Block block) {
        placed.add(key(block));
    }

    /** Whether somebody may break this block now. */
    public boolean mayBreak(Player player, Block block) {
        Round now = round;
        if (now == null || !now.phase().isPlaying() || isFrozen()) {
            return false;
        }
        synchronized (now) {
            if (now.fighter(player.getUniqueId()).filter(Fighter::isAlive).isEmpty()) {
                return false;
            }
            if (now.mutator().needsBreaking()) {
                return buildRule.mayBreakForSpleef(block.getX(), block.getY(), block.getZ(), area(now), keepClear(now),
                        settings.keepClear());
            }
            return buildRule.mayBreak(settings.building(), placed.contains(key(block)), block.getX(), block.getY(),
                    block.getZ(), area(now), keepClear(now), settings.keepClear());
        }
    }

    /** Whether anybody in this round may change blocks at all — for the world's lock. */
    public boolean mayBuildAtAll(Player player) {
        Round now = round;
        if (now == null || !now.phase().isPlaying()) {
            return false;
        }
        boolean rules = settings.building() != Building.NONE || now.mutator().needsBreaking();
        return rules && isIn(player.getUniqueId());
    }

    private static long key(Block block) {
        return ((long) (block.getX() & 0x3FFFFFF) << 38) | ((long) (block.getZ() & 0x3FFFFFF) << 12)
                | (block.getY() & 0xFFF);
    }

    // ================================================================== who and the board

    /** The side of somebody alive in the running round. For the combat rule. */
    public Optional<Integer> sideOf(UUID player) {
        Round now = round;
        if (now == null || !now.phase().isPlaying()) {
            return Optional.empty();
        }
        synchronized (now) {
            return now.fighter(player).filter(Fighter::isAlive).map(Fighter::side);
        }
    }

    public Optional<Fighter> fighter(UUID player) {
        Round now = round;
        if (now == null) {
            return Optional.empty();
        }
        synchronized (now) {
            return now.fighter(player);
        }
    }

    public boolean isCarrying(UUID player) {
        Round now = round;
        if (now == null) {
            return false;
        }
        synchronized (now) {
            return now.carriedBy(player).isPresent();
        }
    }

    /** Whether a side's flag is in the air, thrown. */
    public boolean isFlying(int side) {
        return flying.contains(side);
    }

    public boolean isOneHit() {
        Round now = round;
        return now != null && now.mutator() == Mutator.ONE_HIT && now.phase().isPlaying();
    }

    public boolean isKnockback() {
        Round now = round;
        return now != null && now.mutator() == Mutator.KNOCKBACK && now.phase().isPlaying();
    }

    public boolean isSpleef() {
        Round now = round;
        return now != null && now.mutator() == Mutator.SPLEEF && now.phase().isPlaying();
    }

    /** Uses a double jump if they have one. */
    public boolean doubleJump(UUID player) {
        Round now = round;
        if (now == null) {
            return false;
        }
        synchronized (now) {
            Optional<Fighter> fighter = now.fighter(player);
            if (fighter.isEmpty() || fighter.get().doubleJumps() <= 0) {
                return false;
            }
            fighter.get().doubleJumps(fighter.get().doubleJumps() - 1);
            return true;
        }
    }

    public int doubleJumpsLeft(UUID player) {
        return fighter(player).map(Fighter::doubleJumps).orElse(0);
    }

    private Collection<UUID> audience(Round now) {
        Set<UUID> all = new java.util.LinkedHashSet<>();
        now.fighters().forEach(fighter -> all.add(fighter.id()));
        all.addAll(spectators.watchers());
        return all;
    }

    private Collection<UUID> sideAudience(Round now, int side) {
        return now.onSide(side).stream().map(Fighter::id).toList();
    }

    /** A player's name in their side's colour, as a message value. */
    private static Markup tagged(Round now, Fighter fighter) {
        String hex = now.side(fighter.side()).colour().textColour().asHexString();
        return Markup.of("<" + hex + ">" + fighter.name() + "</" + hex + ">");
    }

    public String scoreLine(Round now) {
        StringBuilder line = new StringBuilder();
        for (Side side : now.sides()) {
            if (!line.isEmpty()) {
                line.append(" <dark_gray>·</dark_gray> ");
            }
            line.append(side.tagged()).append(" <white>").append(now.score(side.index())).append("</white>");
        }
        return line.toString();
    }

    private void board(Round now, long time) {
        MiniMessage mini = MiniMessage.miniMessage();
        long left = Math.max(0, now.endsAt() - time);
        String clock = String.format(Locale.ROOT, "%d:%02d", left / 60_000, left / 1000 % 60);
        List<Component> shared = new ArrayList<>();
        shared.add(mini.deserialize("<gray>" + now.map().title() + " · " + now.type().title()));
        if (now.mutator() != Mutator.NONE) {
            shared.add(mini.deserialize("<light_purple>" + now.mutator().title()));
        }
        shared.add(mini.deserialize((now.phase() == Phase.OVERTIME ? "<red>Overtime " : "<gray>Time ") + "<white>"
                + clock));
        shared.add(Component.empty());
        synchronized (now) {
            for (Side side : now.sides()) {
                String state = now.flagOf(side.index()).map(flag -> switch (flag.state()) {
                    case HOME -> " <green>⚑";
                    case CARRIED -> " <red>✈";
                    case DROPPED -> " <yellow>⚠";
                }).orElse(now.type() == GameType.ONE_FLAG && now.defenders() != side.index() ? " <gray>⚔" : "");
                shared.add(mini.deserialize(side.tagged() + " <white>" + now.score(side.index()) + "<gray>/"
                        + settings.capturesToWin() + state));
            }
            now.flagOf(Flag.NEUTRAL).ifPresent(flag -> shared.add(mini.deserialize("<white>Neutral flag " + switch (
                    flag.state()) {
                case HOME -> "<green>in the middle";
                case CARRIED -> "<red>carried";
                case DROPPED -> "<yellow>dropped";
            })));
            if (settings.controlPoint() && now.map().control() != null) {
                shared.add(mini.deserialize("<gray>Control: " + (now.controlOwner() >= 0
                        ? now.side(now.controlOwner()).tagged() : "<white>nobody")));
            }
        }
        Component title = mini.deserialize("<red><bold>⚑ CAPTURE THE FLAG</bold>");
        Set<UUID> watching = spectators.watchers();
        for (Fighter fighter : now.fighters()) {
            List<Component> lines = new ArrayList<>(shared);
            lines.add(Component.empty());
            lines.add(mini.deserialize("<gray>Captures <white>" + fighter.captures() + " <gray>Kills <white>"
                    + fighter.kills() + " <gray>Returns <white>" + fighter.returns()));
            lines.add(mini.deserialize("<gray>Class <white>" + fighter.role().title()));
            scoreboards.show(fighter.id(), OWNER, Sidebar.of(title, lines), ScoreboardPriority.HIGH);
        }
        for (UUID watcher : watching) {
            scoreboards.show(watcher, OWNER, Sidebar.of(title, shared), ScoreboardPriority.HIGH);
        }
        float share = now.phase() == Phase.OVERTIME ? 1f
                : (float) left / Math.max(1, now.endsAt() - now.startedAt());
        bossBars.showShared(OWNER, "round", audience(now), new BarStyle(mini.deserialize(scoreLine(now)
                + " <dark_gray>|</dark_gray> <white>" + clock), share,
                now.phase() == Phase.OVERTIME ? BossBar.Color.RED : BossBar.Color.WHITE, BossBar.Overlay.PROGRESS),
                BarPriority.HIGH);
    }

    /** Who is in the running round and on which side, for the menus. */
    public Map<Integer, List<String>> roster() {
        Round now = round;
        Map<Integer, List<String>> roster = new LinkedHashMap<>();
        if (now == null) {
            return roster;
        }
        synchronized (now) {
            for (Fighter fighter : now.fighters()) {
                roster.computeIfAbsent(fighter.side(), side -> new ArrayList<>()).add(fighter.name());
            }
        }
        return roster;
    }
}
