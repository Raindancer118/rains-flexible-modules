package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import de.raindancer.core.world.movement.Moves;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * The seconds between pressing the start block and a run actually beginning: a shared boss bar, a
 * tick and a "go" cue, and every participant frozen in place until it reaches zero.
 *
 * <h2>Why participants cannot move</h2>
 * Asked for explicitly: a countdown a runner can spend closing the distance to the goal is not a
 * countdown, it is a five-second head start. Held with Core's {@code Moves.holdInPlace}: only a step
 * into another block is taken back, and the head turn is kept, so looking around while waiting works
 * and nobody's view snaps back every tick.
 *
 * <h2>Why this is its own {@link Listener}, armed and disarmed like an end condition</h2>
 * Same shape as {@link de.raindancer.modules.speedrun.conditions.AdvancementEndCondition}: registered
 * the moment the countdown begins, unregistered the moment it ends, so a freeze from one countdown can
 * never linger and catch a player in a later one.
 */
final class SpeedrunCountdown implements Listener {

    private static final String OWNER = "core";
    private static final String BAR_ID = "speedrun-countdown";
    private static final int SECONDS = 5;

    private final Plugin plugin;
    private final BossBars bossBars;
    private final Effects effects;
    private final Set<UUID> participants;
    private final Runnable onComplete;
    /** Live view of {@code SpeedrunLobby}'s own set — {@code /lemmemove} adds to it after this
     *  countdown has already begun, so a snapshot taken here would miss a release granted mid-freeze. */
    private final Set<UUID> released;

    private int secondsLeft;
    private ScheduledTask ticking;
    private boolean over;

    SpeedrunCountdown(Plugin plugin, BossBars bossBars, Effects effects, Set<UUID> participants,
                      Runnable onComplete, Set<UUID> released) {
        this.plugin = plugin;
        this.bossBars = bossBars;
        this.effects = effects;
        this.participants = participants;
        this.onComplete = onComplete;
        this.released = released;
    }

    /** Starts the countdown. Called exactly once, by {@link SpeedrunLobby#beginCountdown}. */
    void begin() {
        secondsLeft = SECONDS;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        announce();
        ticking = Scheduling.globalTimer(plugin, 20L, 20L, task -> {
            secondsLeft--;
            if (secondsLeft <= 0) {
                task.cancel();
                finish();
            } else {
                announce();
            }
        });
    }

    /**
     * Stops it without ever completing — the plugin going away mid-countdown. Core owns the shared
     * bar and outlives this plugin, so a countdown that just stopped ticking would leave "3" on
     * everybody's screen until Core itself restarted.
     */
    synchronized void cancel() {
        if (over) {
            return;
        }
        over = true;
        if (ticking != null) {
            ticking.cancel();
        }
        HandlerList.unregisterAll(this);
        bossBars.clearShared(OWNER, BAR_ID);
    }

    private void announce() {
        Component text = Component.text(secondsLeft, NamedTextColor.YELLOW);
        bossBars.showShared(OWNER, BAR_ID, participants,
                BarStyle.of(text).progress(secondsLeft / (float) SECONDS).colour(BossBar.Color.GREEN),
                BarPriority.HIGH);
        effects.playForAll(participants, Cues.COUNTDOWN);
    }

    private void finish() {
        synchronized (this) {
            if (over) {
                return;
            }
            over = true;
        }
        HandlerList.unregisterAll(this);
        bossBars.clearShared(OWNER, BAR_ID);
        effects.playForAll(participants, Cues.COUNTDOWN_DONE);
        onComplete.run();
    }

    /**
     * Blocks an actual step, not a look around. {@code MONITOR} would be too late — vanilla has
     * already moved the player by the time a monitor-priority handler sees the event — so this runs
     * at {@code HIGHEST}, the last priority that can still refuse the move itself.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!participants.contains(id) || released.contains(id)) {
            return;
        }
        if (!Moves.changedBlock(event)) {
            return;
        }
        Moves.holdInPlace(event);
    }

}
