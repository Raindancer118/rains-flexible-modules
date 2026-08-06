package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The RUNNING phase's clock: the border's own tick, the boss bar and kill-leaderboard countdown, and — the
 * one thing every caller of this class has to get right — asking {@link RoundExpiryService} whether the
 * round should actually stop, every single second, rather than deciding that here.
 *
 * <h2>A found bug, and the reason this class exists as it does</h2>
 * The engine this replaces ended a round unconditionally the moment elapsed time reached
 * {@code game.duration} — {@code if (elapsed >= gameDuration) { session.declareTimeout(); return; }}, right
 * inside its own tick method. That is a straightforward, sensible-looking line, and it is also exactly what
 * {@link RoundExpiryService} was written to replace: the whole point of that class is that the scheduled
 * length running out is a <em>question</em>, put to whoever can see the room, not an automatic ending. A
 * literal port of the old tick method would have shipped a working {@code RoundExpiryService} that a working
 * {@code GameTimerService} never gave a chance to be asked — the round would already be {@code FINISHED} by
 * the time its deadline logic ran.
 *
 * <p>So this class does not compare elapsed time to {@code game.duration} at all. Every tick calls
 * {@link RoundExpiryService#tick}, unconditionally, and <em>that</em> class's own deadline — which starts at
 * {@code settings.roundDuration()} and only ever moves out from there — is the one clock this service reads
 * back for the boss bar's countdown. A round that has been extended shows the extended time remaining, not a
 * timer that reads zero while gamemasters are still being asked what to do.
 *
 * <h2>Why the boss bar and the scoreboard are Core's</h2>
 * {@link BossBars} and {@link Scoreboards} already arbitrate who else on the server is holding a player's
 * one boss bar and one sidebar — see their own class notes — which a hand-rolled {@code Bukkit.createBossBar}
 * cannot do at all. This class owns one slot on each, keyed by {@link #OWNER}, and never touches a Bukkit
 * scoreboard or boss bar object directly.
 *
 * <h2>Why "who sees it" is a seam</h2>
 * Working out who is online belongs to whoever wires this against a real {@code Server}; a test hands in a
 * fixed {@link OnlineAudience} instead. Likewise the effect that plays the instant the grace period ends —
 * cues and glow are Core's {@code Effects}, reached for by whoever wires this — arrives as
 * {@link GraceEffects} so this class never has to know a potion effect exists.
 *
 * <h2>Why the repeating timer is also a seam over {@code Scheduling}</h2>
 * The source engine owned its own {@code BukkitTask}, from {@code Bukkit.getScheduler().runTaskTimer}. That
 * call does not exist on Folia. The correct replacement is {@link Scheduling#globalTimer}, and this class
 * really does call it — in {@link #viaScheduling}, the real {@link RoundTicker} — rather than leaving the
 * scheduling for "whoever wires it" the way {@code DeathmatchService}'s own countdown does. The seam exists
 * for exactly the reason {@code HttpApiService.ServerThread} does: {@link #viaScheduling} needs a live
 * server to actually schedule anything, and a test needs to drive {@link #tick} by hand instead — see
 * {@link #manual()}.
 */
public final class GameTimerService implements IHungerGamesService {

    /** The one boss bar slot, and the one sidebar slot, this service claims on every viewer. */
    public static final String OWNER = "hungergames-timer";

    /** Everybody currently online — whoever should see the boss bar and the leaderboard right now. */
    @FunctionalInterface
    public interface OnlineAudience {
        Collection<UUID> onlineNow();
    }

    /** What happens, in the world, the instant the grace period ends. Bukkit's job, handed in as a seam. */
    @FunctionalInterface
    public interface GraceEffects {
        void gracePeriodEnded(Collection<UUID> stillProtected);
    }

    /** Runs {@code task} once a second until told to stop — the one thing about this class that needs a
     * live server, kept behind a seam for the same reason {@code HttpApiService.ServerThread} is. */
    @FunctionalInterface
    public interface RoundTicker {
        AutoCloseable everySecond(Runnable task);
    }

    private final GameSession session;
    private final VirtualTime virtualTime;
    private final BorderService border;
    private final RoundExpiryService roundExpiry;
    private final Supplier<List<BorderPhaseConfig>> borderPhases;
    private final BossBars bossBars;
    private final Scoreboards scoreboards;
    private final OnlineAudience audience;
    private final GraceEffects graceEffects;
    private final RoundTicker ticker;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private boolean graceActive;
    private AutoCloseable runningTimer;

    public GameTimerService(GameSession session, VirtualTime virtualTime, BorderService border,
                             RoundExpiryService roundExpiry, Supplier<List<BorderPhaseConfig>> borderPhases,
                             BossBars bossBars, Scoreboards scoreboards, OnlineAudience audience,
                             GraceEffects graceEffects, RoundTicker ticker) {
        this.session = session;
        this.virtualTime = virtualTime;
        this.border = border;
        this.roundExpiry = roundExpiry;
        this.borderPhases = borderPhases;
        this.bossBars = bossBars;
        this.scoreboards = scoreboards;
        this.audience = audience;
        this.graceEffects = graceEffects;
        this.ticker = ticker;
    }

    /**
     * The real ticker: onto Core's own repeating scheduler, once a second.
     *
     * <p>Deliberately not {@code Bukkit.getScheduler().runTaskTimer} — see the class note — and not called
     * directly by anything but this method and a test: everything else reaches {@link #tick} through the
     * {@link AutoCloseable} handle {@link Scheduling#globalTimer} hands back, wrapped so {@link #stop} has
     * one uniform way to cancel it whichever seam produced it.
     */
    public static RoundTicker viaScheduling(Plugin plugin) {
        return task -> {
            var scheduled = Scheduling.globalTimer(plugin, 20L, 20L, handle -> task.run());
            return scheduled::cancel;
        };
    }

    /** Never actually schedules anything. For tests, which drive {@link #tick} themselves. */
    public static RoundTicker manual() {
        return task -> () -> { };
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Starts the round's clock at zero and its repeating tick. Call once, on the move into
     * {@link GamePhase#RUNNING}. */
    public void start() {
        virtualTime.start();
        border.start();
        graceActive = !settings.gracePeriod().isZero();
        refreshDisplaysFor(audience.onlineNow());
        beginTicking();
    }

    /** Picks a mid-round session back up after a restart, and resumes the repeating tick. */
    public void resume(Duration elapsedBefore, int borderPhaseIndex) {
        virtualTime.resumeAt(elapsedBefore);
        border.resumeAt(borderPhaseIndex);
        graceActive = virtualTime.elapsed().compareTo(settings.gracePeriod()) < 0;
        refreshDisplaysFor(audience.onlineNow());
        beginTicking();
    }

    private void beginTicking() {
        stopTicking();
        runningTimer = ticker.everySecond(this::tick);
    }

    private void stopTicking() {
        if (runningTimer == null) {
            return;
        }
        try {
            runningTimer.close();
        } catch (Exception ignored) {
            // A cancel that fails leaves nothing dangerous behind — the task simply outlives the round by
            // at most one more second, and the next tick's own phase check turns it back off.
        }
        runningTimer = null;
    }

    /** Clears every viewer's boss bar and sidebar, and cancels the repeating tick. Call once the round
     * leaves {@link GamePhase#RUNNING}. */
    public void stop() {
        stopTicking();
        for (UUID uuid : audience.onlineNow()) {
            bossBars.clear(uuid, OWNER);
            scoreboards.clear(uuid, OWNER);
        }
    }

    /** Shows the running round's displays to somebody who joined mid-round. A no-op outside RUNNING. */
    public void addViewer(UUID uuid) {
        if (session.phase() == GamePhase.RUNNING) {
            show(uuid);
        }
    }

    public void removeViewer(UUID uuid) {
        bossBars.clear(uuid, OWNER);
        scoreboards.clear(uuid, OWNER);
    }

    /** Whether tributes are still under the round-start invulnerability — for a damage listener to consult. */
    public boolean isGraceActive() {
        return graceActive;
    }

    /**
     * One second of a running round.
     *
     * <p>Ticks the border, asks {@link RoundExpiryService} whether the round is over — every time, whatever
     * else happens this tick, see the class note above — and refreshes the boss bar and leaderboard for
     * whoever is watching. Idempotent to call once the round has already left RUNNING: it simply clears the
     * displays and returns.
     */
    public void tick() {
        if (session.phase() != GamePhase.RUNNING) {
            stop();
            return;
        }

        Duration elapsed = virtualTime.elapsed();
        Duration grace = settings.gracePeriod();

        if (graceActive && elapsed.compareTo(grace) >= 0) {
            graceActive = false;
            graceEffects.gracePeriodEnded(aliveOnline());
        }

        roundExpiry.tick(elapsed);

        border.tick(border.currentSettings(borderPhases.get()));
        refreshDisplaysFor(audience.onlineNow());
    }

    // ==================== displays ====================

    private void refreshDisplaysFor(Collection<UUID> viewers) {
        for (UUID uuid : viewers) {
            show(uuid);
        }
    }

    private void show(UUID uuid) {
        bossBars.show(uuid, OWNER, bossBarStyle(), BarPriority.HIGH);
        scoreboards.show(uuid, OWNER, leaderboard(), ScoreboardPriority.NORMAL);
    }

    private BarStyle bossBarStyle() {
        Duration elapsed = virtualTime.elapsed();

        if (graceActive) {
            Duration grace = settings.gracePeriod();
            long remainingSeconds = Math.max(0, grace.minus(elapsed).getSeconds());
            float progress = grace.isZero() ? 1f
                    : (float) clamp01((double) remainingSeconds / grace.getSeconds());
            return BarStyle.of(Component.text("PROTECTION — ", NamedTextColor.GREEN)
                            .append(Component.text(remainingSeconds + "s", NamedTextColor.YELLOW)))
                    .progress(progress)
                    .colour(BossBar.Color.GREEN);
        }

        Duration deadline = roundExpiry.deadline();
        Duration remaining = deadline.minus(elapsed);
        long minutes = Math.max(0, remaining.toMinutes());
        long seconds = Math.max(0, remaining.toSecondsPart());
        float progress = deadline.isZero() ? 0f
                : (float) clamp01((double) Math.max(0, remaining.toMillis()) / deadline.toMillis());
        BossBar.Color colour = minutes <= 5 ? BossBar.Color.RED
                : minutes <= 15 ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        return BarStyle.of(Component.text(String.format("THE GAMES: %d:%02d — Tributes: %d",
                        minutes, seconds, session.participants().aliveCount()), NamedTextColor.WHITE))
                .progress(progress)
                .colour(colour);
    }

    private Sidebar leaderboard() {
        List<Component> lines = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : session.kills().top(10)) {
            String name = session.participants().nameOf(entry.getKey()).orElse("?");
            lines.add(Component.text(name + ": " + entry.getValue(), NamedTextColor.GRAY));
        }
        lines.add(Component.text("Alive: " + session.participants().aliveCount(), NamedTextColor.RED));
        return Sidebar.of(Component.text("KILL LEADERBOARD", NamedTextColor.GOLD), lines);
    }

    private Collection<UUID> aliveOnline() {
        return audience.onlineNow().stream()
                .filter(session::isWhitelisted)
                .filter(uuid -> session.participants().isAlive(uuid))
                .toList();
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    @Override
    public String describe() {
        return "the round clock: the border's tick, the boss bar and kill leaderboard, and asking "
                + "RoundExpiryService whether it is over";
    }
}
