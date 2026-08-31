package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.SpeedrunSession;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a hunt says out loud while it is running: a Runner changing dimension, a death, the clock
 * running out, the dragon losing health.
 *
 * <h2>Why a Manhunt needs narration at all</h2>
 * Both sides are playing the same game in different places, and almost nothing either of them does is
 * visible to the other. Without a line in chat, a Hunter learns that the Runners reached the End when
 * the hunt simply ends. Every announcement here is a fact one side already knows being told to the
 * other, never information neither of them had — where a Runner is remains the compass' business, and
 * the compass has its own settings for how much it gives away.
 *
 * <h2>Its own timer, rather than a third hook on {@code ManhuntService}</h2>
 * The clock warnings need a tick; the service already has one, but its hooks are deliberately one
 * caller each (see {@code ManhuntService.onStart}) and the wiring class is already stacking two
 * concerns behind both of them. A second one-second timer, armed and disarmed with the hunt exactly
 * like {@code TrackerCompassService}'s own sweep, costs less than widening that contract again.
 */
public final class ManhuntNarrator {

    /** Five minutes, a minute, ten seconds — the points at which a clock is worth saying out loud. */
    private static final double[] CLOCK_MARKS = {300, 60, 10};

    /** Half and a quarter of the dragon's health. Fractions, not hit points, so a modified maximum
     *  health still announces the same two moments. */
    private static final double[] DRAGON_MARKS = {0.5, 0.25};

    private final Plugin plugin;
    private final ManhuntService manhunt;
    private final Messages messages;

    private final Thresholds clock = new Thresholds(CLOCK_MARKS);
    private final Thresholds dragon = new Thresholds(DRAGON_MARKS);

    private volatile ManhuntSettings settings;
    private volatile ScheduledTask ticking;
    private volatile double lastSecondsLeft = Double.MAX_VALUE;

    public ManhuntNarrator(Plugin plugin, ManhuntService manhunt, Messages messages,
                          ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    // ------------------------------------------------------------------------ armed with the hunt

    /** A hunt has started: every mark is armed again and the clock watch begins. */
    public void arm() {
        clock.reset();
        dragon.reset();
        lastSecondsLeft = Double.MAX_VALUE;
        stop();
        ticking = Scheduling.globalTimer(plugin, 20L, 20L, handle -> tickClock());
    }

    /** The hunt is over — nothing left to narrate. */
    public void disarm() {
        stop();
    }

    private void stop() {
        ScheduledTask running = ticking;
        if (running != null) {
            running.cancel();
        }
        ticking = null;
    }

    private void tickClock() {
        ManhuntSettings config = settings;
        if (!config.narrateTimeLeft()
                || config.hunterWin() != ManhuntSettings.HunterWinCondition.TIMEOUT) {
            return;
        }
        SpeedrunSession session = manhunt.session().orElse(null);
        if (session == null) {
            return;
        }
        double total = Duration.ofMinutes(config.hunterTimeoutMinutesClamped()).toSeconds();
        double left = total - session.elapsed().toSeconds();
        double was = lastSecondsLeft;
        lastSecondsLeft = left;
        clock.crossed(was, left).ifPresent(mark -> tellEverybody("manhunt.narrate.time-left",
                "time", spell(mark.intValue())));
    }

    /** Turns a mark in seconds into the words a person would actually use for it. */
    private static String spell(int seconds) {
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + " seconds";
    }

    // ------------------------------------------------------------------------ told by the listener

    /** A Runner has arrived in another world. {@code friendlyWorld} is already the readable name. */
    public void runnerChangedWorld(String runner, String friendlyWorld) {
        if (settings.narrateDimensions()) {
            tellEverybody("manhunt.narrate.dimension", "runner", runner, "where", friendlyWorld);
        }
    }

    /** A Runner died and has lives left. */
    public void runnerDied(String runner, int livesLeft) {
        if (settings.narrateDeaths()) {
            tellEverybody("manhunt.narrate.runner-died", "runner", runner,
                    "lives", String.valueOf(livesLeft));
        }
    }

    /** A Runner is out of the hunt for good. */
    public void runnerEliminated(String runner, int standing) {
        if (settings.narrateDeaths()) {
            tellEverybody("manhunt.narrate.runner-out", "runner", runner,
                    "left", String.valueOf(standing));
        }
    }

    /** A Hunter died — no consequence to announce, but the Runners have earned knowing. */
    public void hunterDied(String hunter) {
        if (settings.narrateDeaths()) {
            tellEverybody("manhunt.narrate.hunter-died", "hunter", hunter);
        }
    }

    /** The dragon's health has moved. Fractions of its maximum, not hit points. */
    public void dragonHealth(double wasFraction, double isFraction) {
        if (!settings.narrateDragon()) {
            return;
        }
        dragon.crossed(wasFraction, isFraction).ifPresent(mark ->
                tellEverybody("manhunt.narrate.dragon", "percent",
                        String.valueOf((int) Math.round(mark * 100))));
    }

    /** A fresh dragon — a second one summoned with end crystals is a second fight, not the same one. */
    public void dragonReset() {
        dragon.reset();
    }

    // ------------------------------------------------------------------------ saying it

    private void tellEverybody(String key, String... values) {
        if (messages == null) {
            return;
        }
        Set<UUID> roster = manhunt.teams().everybody();
        for (UUID id : roster) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, key, (Object[]) values);
            }
        }
    }

    public String describe() {
        return "what a hunt says out loud: dimensions, deaths, the clock and the dragon";
    }
}
