package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The splits on screen, each viewer where they chose: the sidebar, a boss bar, beside the clock on
 * the action bar, or nowhere. Redrawn once a second with the clock.
 *
 * <h2>What each one shows</h2>
 * The sidebar lists the run's splits so far, newest at the bottom, each with its delta to the
 * viewer's personal best (or to the record, for somebody without one), then the next milestone, the
 * pearl count and whatever the game mode adds. The boss bar is the last split and the next one, its
 * fill the share of milestones passed. The action bar is the last split's delta beside the clock —
 * {@link #actionBarSuffix}, which the clock asks for.
 */
public final class SpeedrunHud {

    static final String OWNER = "speedrun-splits";
    /** How many past splits the sidebar lists, leaving room for the next one and a mode's lines. */
    static final int SPLITS_SHOWN = 9;

    private final Scoreboards scoreboards;
    private final BossBars bossBars;
    private final SpeedrunPlayerPrefs prefs;
    private final Supplier<SpeedrunSettings> settings;
    private final SpeedrunTimerDisplay.Ticker ticker;
    /** Who has something of ours on screen, and where. */
    private final Map<UUID, SpeedrunHudMode> showing = new ConcurrentHashMap<>();

    private volatile SpeedrunSession session;
    private volatile SpeedrunSplitTracker tracker;
    private volatile Supplier<Collection<UUID>> audience = Set::of;
    private AutoCloseable running;

    public SpeedrunHud(Scoreboards scoreboards, BossBars bossBars, SpeedrunPlayerPrefs prefs,
                       Supplier<SpeedrunSettings> settings, SpeedrunTimerDisplay.Ticker ticker) {
        this.scoreboards = scoreboards;
        this.bossBars = bossBars;
        this.prefs = prefs;
        this.settings = settings;
        this.ticker = ticker;
    }

    /** Where {@code viewer} sees the splits — their own choice, or the server's default. */
    public SpeedrunHudMode modeOf(UUID viewer) {
        SpeedrunHudMode fallback = settings.get().hudDefaultOrSidebar();
        return prefs == null ? fallback : prefs.hudOf(viewer, fallback);
    }

    /** Starts drawing {@code session}'s splits to the racers and to whoever {@code onlookers} names. */
    public synchronized void start(SpeedrunSession session, SpeedrunSplitTracker tracker,
                                   Supplier<Collection<UUID>> onlookers) {
        stop();
        this.session = session;
        this.tracker = tracker;
        this.audience = onlookers == null ? Set::of : onlookers;
        draw();
        running = ticker.everySecond(this::draw);
        session.onFinish(outcome -> {
            draw();     // the finish split, once more
            stopTicking();
        });
    }

    /** Takes everything off every screen — the run was forgotten, or the plugin is going away. */
    public synchronized void stop() {
        stopTicking();
        for (UUID viewer : Set.copyOf(showing.keySet())) {
            clear(viewer);
        }
        session = null;
        tracker = null;
    }

    /** Redraws one viewer at once — their choice just changed. */
    public void redraw(UUID viewer) {
        SpeedrunSession now = session;
        SpeedrunSplitTracker splits = tracker;
        if (now == null || splits == null) {
            clear(viewer);
            return;
        }
        drawFor(viewer, now, splits);
    }

    void draw() {
        SpeedrunSession now = session;
        SpeedrunSplitTracker splits = tracker;
        if (now == null || splits == null) {
            return;
        }
        Set<UUID> viewers = new HashSet<>(now.participants());
        viewers.addAll(audience.get());
        for (UUID viewer : viewers) {
            drawFor(viewer, now, splits);
        }
        for (UUID gone : Set.copyOf(showing.keySet())) {
            if (!viewers.contains(gone)) {
                clear(gone);
            }
        }
    }

    private void drawFor(UUID viewer, SpeedrunSession now, SpeedrunSplitTracker splits) {
        SpeedrunHudMode mode = modeOf(viewer);
        SpeedrunHudMode before = showing.get(viewer);
        if (before != null && before != mode) {
            clear(viewer);
        }
        switch (mode) {
            case SIDEBAR -> {
                if (scoreboards != null) {
                    scoreboards.show(viewer, OWNER, sidebar(viewer, now, splits), ScoreboardPriority.NORMAL);
                    showing.put(viewer, mode);
                }
            }
            case BOSSBAR -> {
                if (bossBars != null) {
                    bossBars.show(viewer, OWNER, bar(viewer, splits), BarPriority.NORMAL);
                    showing.put(viewer, mode);
                }
            }
            case ACTIONBAR -> showing.put(viewer, mode);   // drawn by the clock, see actionBarSuffix
            case OFF -> clear(viewer);
        }
    }

    private void clear(UUID viewer) {
        SpeedrunHudMode was = showing.remove(viewer);
        if (was == SpeedrunHudMode.SIDEBAR && scoreboards != null) {
            scoreboards.clear(viewer, OWNER);
        } else if (was == SpeedrunHudMode.BOSSBAR && bossBars != null) {
            bossBars.clear(viewer, OWNER);
        }
    }

    Sidebar sidebar(UUID viewer, SpeedrunSession now, SpeedrunSplitTracker splits) {
        List<Component> lines = new ArrayList<>();
        List<SpeedrunSplitTracker.Split> done = splits.splits();
        for (SpeedrunSplitTracker.Split split : done.subList(Math.max(0, done.size() - SPLITS_SHOWN), done.size())) {
            lines.add(splitLine(split, splits.compare(split, viewer)));
        }
        if (now.state() != SpeedrunState.FINISHED) {
            splits.next().ifPresent(next -> lines.add(Component.text("» " + next.label(), NamedTextColor.GRAY)));
            if (splits.timelineHasNo(SpeedrunMilestones.PEARLS)) {
                lines.add(Component.text("Pearls " + splits.pearls() + "/" + settings.get().pearlsToCollect(),
                        NamedTextColor.DARK_AQUA));
            }
        }
        lines.addAll(splits.hudLinesFor(viewer));
        Component title = Component.text("Speedrun ", NamedTextColor.GOLD)
                .append(SpeedrunTimerDisplay.format(now.elapsed()));
        return Sidebar.of(title, lines.subList(0, Math.min(lines.size(), Sidebar.MAX_LINES)));
    }

    static Component splitLine(SpeedrunSplitTracker.Split split, SpeedrunComparison comparison) {
        Component line = Component.text(split.milestone().label() + " ",
                        comparison.gold() ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                .append(Component.text(SpeedrunTimerDisplay.plain(split.at()), NamedTextColor.YELLOW));
        Optional<Duration> delta = comparison.vsPersonalBest().or(comparison::vsRecord);
        return delta.map(difference -> line.append(Component.text(" ")).append(SpeedrunComparison.delta(difference)))
                .orElse(line);
    }

    BarStyle bar(UUID viewer, SpeedrunSplitTracker splits) {
        Component text = splits.last()
                .map(split -> splitLine(split, splits.compare(split, viewer)))
                .orElse(Component.text("No split yet", NamedTextColor.GRAY));
        Optional<SpeedrunMilestone> next = splits.next();
        if (next.isPresent()) {
            text = text.append(Component.text("  » " + next.get().label(), NamedTextColor.GRAY));
        }
        int total = splits.milestones().size();
        float progress = total == 0 ? 0f : splits.splits().size() / (float) total;
        return BarStyle.of(text).progress(Math.min(1f, progress)).colour(BossBar.Color.YELLOW);
    }

    /** What the clock shows after the time, for a viewer who chose the action bar — empty otherwise. */
    public Component actionBarSuffix(UUID viewer) {
        SpeedrunSplitTracker splits = tracker;
        if (splits == null || modeOf(viewer) != SpeedrunHudMode.ACTIONBAR) {
            return Component.empty();
        }
        return splits.last()
                .map(split -> Component.text("  ").append(splitLine(split, splits.compare(split, viewer))))
                .orElse(Component.empty());
    }

    private void stopTicking() {
        AutoCloseable was = running;
        running = null;
        if (was != null) {
            try {
                was.close();
            } catch (Exception ignored) {
                // a cancel that fails leaves a tick that finds nothing to draw
            }
        }
    }
}
