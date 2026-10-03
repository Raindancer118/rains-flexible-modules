package de.raindancer.modules.speedrun.manhunt.hud;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.service.HunterHoldListener;
import de.raindancer.modules.speedrun.manhunt.service.PositionShare;
import de.raindancer.modules.speedrun.manhunt.stats.HuntLog;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Point;
import de.raindancer.modules.speedrun.manhunt.util.Threads;
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
import java.util.function.Supplier;

/**
 * One hunt's heartbeat, once a second: the hunt's lines on the run's sidebar, the head-start bar,
 * the Runners' glow, and the distance everybody travelled.
 *
 * <p>The sidebar itself is the lobby's — one per player, wherever they chose to see it. This only
 * says what Manhunt adds to it ({@link #linesFor}), through {@code SpeedrunRun.hudLines}.
 *
 * <h2>Why one timer for all four</h2>
 * They all need the same thing — where everybody is right now — and reading every position once a
 * second for four features is the same cost as for one. Positions are read on the global region
 * thread, as the compass sweep does; the only write to a player (the glow) hops onto their own.
 */
public final class HuntTicker {

    /** The bar owner, in Core's per-owner arbitration. */
    static final String OWNER = "manhunt";
    static final String BAR = "head-start";
    /** Further than this in one second is a teleport, not a journey. */
    static final double TELEPORT = 64;

    private final Plugin plugin;
    private final Hunt hunt;
    private final HuntLog log;
    private final Supplier<Duration> clock;
    private final HunterHoldListener hold;
    private final long headStartEndsAt;
    private final long headStartMillis;
    private final Supplier<ManhuntSettings> settings;
    private final Messages messages;
    private final BossBars bars;
    private final Consumer<Player> glow;
    private final Map<UUID, Point> last = new ConcurrentHashMap<>();
    /** Where everybody was at the last beat, and what they are called — what {@link #linesFor} reads. */
    private volatile Map<UUID, HudLines.Where> where = Map.of();
    private volatile Map<UUID, String> names = Map.of();
    private volatile long lastSecond;
    private volatile boolean barUp;
    private volatile ScheduledTask task;

    /**
     * @param headStartSeconds 0 for none; the bar counts it down from now
     * @param glow             how a Runner is made to glow, on their own thread
     */
    public HuntTicker(Plugin plugin, Hunt hunt, HuntLog log, Supplier<Duration> clock, HunterHoldListener hold,
                      int headStartSeconds, long nowMillis, Supplier<ManhuntSettings> settings, Messages messages,
                      BossBars bars, Consumer<Player> glow) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hold = hold;
        this.headStartMillis = Math.max(0, headStartSeconds) * 1000L;
        this.headStartEndsAt = nowMillis + headStartMillis;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.bars = bars;
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
        Map<UUID, String> known = new LinkedHashMap<>();
        for (UUID id : hunt.everybody()) {
            Player player = online.get(id);
            known.put(id, player != null ? player.getName() : nameOf(id));
        }
        this.names = Map.copyOf(known);
        this.where = Map.copyOf(where);
    }

    /**
     * What Manhunt adds to {@code viewer}'s splits sidebar: the Runners — where each is, how far from a
     * Hunter looking, who is caught — the pack, and the viewer's own side. As of the last beat.
     */
    public List<Component> linesFor(UUID viewer) {
        List<Component> lines = new ArrayList<>();
        for (HudLines.Line line : HudLines.build(viewer, hunt, names, where, settings.get().runnerLivesClamped())) {
            lines.add(messages.get("manhunt.hud." + line.key(), (Object[]) line.placeholders()));
        }
        return lines;
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

    private String nameOf(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? "somebody" : name;
    }

    private void tell(Iterable<Player> players, String key, String... placeholders) {
        for (Player player : players) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }

    /** The hunt is over: the timer stops and the bar goes. The sidebar is the lobby's to take down. */
    public void stop() {
        ScheduledTask running = task;
        if (running != null) {
            running.cancel();
        }
        task = null;
        if (bars != null && barUp) {
            bars.clearShared(OWNER, BAR);
            barUp = false;
        }
    }
}
