package de.raindancer.modules.manhunt.hud;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.service.HunterHoldListener;
import de.raindancer.modules.manhunt.service.PositionShare;
import de.raindancer.modules.manhunt.stats.HuntLog;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import de.raindancer.modules.manhunt.util.Threads;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One hunt's heartbeat, once a second: the sidebar, the head-start bar, the Runners' glow, and the
 * distance everybody travelled.
 *
 * <h2>Why one timer for all four</h2>
 * They all need the same thing — where everybody is right now — and reading every position once a
 * second for four features is the same cost as for one. Positions are read on the global region
 * thread, as the compass sweep does; the only write to a player (the glow) hops onto their own.
 */
public final class HuntTicker {

    /** The sidebar and bar owner, in Core's per-owner arbitration. */
    static final String OWNER = "manhunt";
    static final String BAR = "head-start";
    /** Further than this in one second is a teleport, not a journey. */
    static final double TELEPORT = 64;

    /** Each player's own "show me the sidebar" — on until they hide it. */
    public static final PlayerSwitch SIDEBAR = new PlayerSwitch("manhunt", "sidebar", true);

    private final Plugin plugin;
    private final Hunt hunt;
    private final HuntLog log;
    private final Supplier<Duration> clock;
    private final HunterHoldListener hold;
    private final long headStartEndsAt;
    private final long headStartMillis;
    private final Supplier<ManhuntSettings> settings;
    private final Messages messages;
    private final Scoreboards scoreboards;
    private final BossBars bars;
    private final Predicate<Player> wantsSidebar;
    private final Consumer<Player> glow;
    private final Map<UUID, Point> last = new ConcurrentHashMap<>();
    private final Set<UUID> showing = ConcurrentHashMap.newKeySet();
    private volatile long lastSecond;
    private volatile boolean barUp;
    private volatile ScheduledTask task;

    /**
     * @param headStartSeconds 0 for none; the bar counts it down from now
     * @param wantsSidebar     a player's own switch — {@link #SIDEBAR} on a server
     * @param glow             how a Runner is made to glow, on their own thread
     */
    public HuntTicker(Plugin plugin, Hunt hunt, HuntLog log, Supplier<Duration> clock, HunterHoldListener hold,
                      int headStartSeconds, long nowMillis, Supplier<ManhuntSettings> settings, Messages messages,
                      Scoreboards scoreboards, BossBars bars, Predicate<Player> wantsSidebar, Consumer<Player> glow) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hold = hold;
        this.headStartMillis = Math.max(0, headStartSeconds) * 1000L;
        this.headStartEndsAt = nowMillis + headStartMillis;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scoreboards = scoreboards;
        this.bars = bars;
        this.wantsSidebar = Objects.requireNonNull(wantsSidebar, "wantsSidebar");
        this.glow = Objects.requireNonNull(glow, "glow");
    }

    public void start() {
        task = Scheduling.globalTimer(plugin, 20L, 20L, handle -> step(System.currentTimeMillis()));
    }

    /** One beat. Public for the tests, which cannot wait a second. */
    public void step(long nowMillis) {
        ManhuntSettings config = settings.get();
        Map<UUID, Player> online = new LinkedHashMap<>();
        Map<UUID, HudLines.Where> where = new LinkedHashMap<>();
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                last.remove(id);
                continue;
            }
            online.put(id, player);
            Location at = player.getLocation();
            String world = at.getWorld() == null ? "" : at.getWorld().getName();
            Point point = new Point(world, at.getX(), at.getY(), at.getZ());
            where.put(id, new HudLines.Where(point, at.getWorld() == null ? "Overworld"
                    : PositionShare.dimension(at.getWorld().getEnvironment())));
            Point before = last.put(id, point);
            if (before != null && before.worldName().equals(world)) {
                double step = Math.hypot(point.x() - before.x(), point.z() - before.z());
                if (step <= TELEPORT) {
                    log.travelled(id, step);
                }
            }
        }
        long second = clock.get().toSeconds();
        for (GlowSchedule.Cue cue : GlowSchedule.between(lastSecond, second, config.glowEveryMinutesClamped())) {
            if (cue == GlowSchedule.Cue.WARN) {
                tell(online.values(), "manhunt.glow.soon");
            } else {
                for (UUID runner : hunt.livingRunners()) {
                    Player player = online.get(runner);
                    if (player != null) {
                        Threads.entity(plugin, player, () -> glow.accept(player));
                    }
                }
                tell(online.values(), "manhunt.glow.now", "seconds", String.valueOf(config.glowSecondsClamped()));
            }
        }
        lastSecond = Math.max(lastSecond, second);
        headStartBar(online.keySet(), nowMillis, config);
        sidebars(online, where, config);
    }

    private void headStartBar(Set<UUID> audience, long nowMillis, ManhuntSettings config) {
        if (bars == null) {
            return;
        }
        long left = headStartEndsAt - nowMillis;
        boolean holding = hold == null || hold.isHolding();
        if (config.hudHeadStartBar() && headStartMillis > 0 && left > 0 && holding) {
            Component text = messages.get("manhunt.bar.head-start", "seconds", String.valueOf((left + 999) / 1000));
            bars.showShared(OWNER, BAR, audience, BarStyle.of(text).progress((float) left / headStartMillis)
                    .colour(BossBar.Color.YELLOW), BarPriority.HIGH);
            barUp = true;
        } else if (barUp) {
            bars.clearShared(OWNER, BAR);
            barUp = false;
        }
    }

    private void sidebars(Map<UUID, Player> online, Map<UUID, HudLines.Where> where, ManhuntSettings config) {
        if (scoreboards == null) {
            return;
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID id : hunt.everybody()) {
            Player player = online.get(id);
            names.put(id, player != null ? player.getName() : nameOf(id));
        }
        for (Map.Entry<UUID, Player> entry : online.entrySet()) {
            UUID id = entry.getKey();
            if (!config.hudSidebar() || !wantsSidebar.test(entry.getValue())) {
                if (showing.remove(id)) {
                    scoreboards.clear(id, OWNER);
                }
                continue;
            }
            List<Component> lines = new ArrayList<>();
            for (HudLines.Line line : HudLines.build(id, hunt, clock.get().toMillis(), names, where,
                    config.runnerLivesClamped())) {
                lines.add(messages.get("manhunt.hud." + line.key(), (Object[]) line.placeholders()));
            }
            scoreboards.show(id, OWNER, Sidebar.of(messages.get("manhunt.hud.title"), lines),
                    ScoreboardPriority.HIGH);
            showing.add(id);
        }
    }

    private String nameOf(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? "somebody" : name;
    }

    private void tell(Iterable<Player> players, String key, String... placeholders) {
        for (Player player : players) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }

    /** The hunt is over: the timer stops, and every sidebar and the bar go. */
    public void stop() {
        ScheduledTask running = task;
        if (running != null) {
            running.cancel();
        }
        task = null;
        if (scoreboards != null) {
            for (UUID id : Set.copyOf(showing)) {
                scoreboards.clear(id, OWNER);
            }
        }
        showing.clear();
        if (bars != null && barUp) {
            bars.clearShared(OWNER, BAR);
            barUp = false;
        }
    }
}
