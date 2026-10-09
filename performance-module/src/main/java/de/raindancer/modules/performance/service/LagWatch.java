package de.raindancer.modules.performance.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.listener.TickListener;
import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.model.SampleRing;
import de.raindancer.modules.performance.model.Spike;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.rules.LagRule;
import de.raindancer.modules.performance.rules.NoticeRule;
import de.raindancer.modules.performance.store.ReportStore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Every five seconds: is the server lagging or did it spike? If so, and staff have not just been told
 * the same, look at what it was busy with and what the chunks hold, write a report, tell staff.
 * The census reads every world — it runs on the global region and only on a single-threaded server.
 */
public final class LagWatch implements IPerformanceService, AutoCloseable {

    private static final long EVERY_TICKS = 100;

    private final Plugin plugin;
    private final TickWindow window;
    private final SampleRing samples;
    private final TickListener ticks;
    private final Diagnosis diagnosis;
    private final ReportStore store;
    private final Notifier notifier;
    private final LongSupplier clock;
    private volatile PerformanceSettings settings;
    private NoticeRule.Last last = NoticeRule.Last.NEVER;
    private long lastLook = System.nanoTime();
    private ScheduledTask task;

    public LagWatch(Plugin plugin, TickWindow window, SampleRing samples, TickListener ticks, Diagnosis diagnosis,
                    ReportStore store, Notifier notifier, LongSupplier clock, PerformanceSettings settings) {
        this.plugin = plugin;
        this.window = window;
        this.samples = samples;
        this.ticks = ticks;
        this.diagnosis = diagnosis;
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void settings(PerformanceSettings changed) {
        this.settings = changed;
    }

    public void start() {
        task = Scheduling.globalTimer(plugin, EVERY_TICKS, EVERY_TICKS, ignored -> look());
    }

    private void look() {
        PerformanceSettings now = settings;
        long since = lastLook;
        lastLook = System.nanoTime();
        if (!now.watch()) {
            return;
        }
        LagRule.State state = new LagRule(now.strainedMs(), now.spikeMs()).judge(window);
        List<Spike> spikes = ticks.spikesSince(since);
        if (state != LagRule.State.LAGGING && !spikes.isEmpty()) {
            state = LagRule.State.SPIKE;
        }
        NoticeRule rule = new NoticeRule(Duration.ofMinutes(now.quietMinutes()));
        if (!rule.shouldTell(last, clock.getAsLong(), state, Set.of()) && !lookAgain(state)) {
            return;
        }
        List<Finding> findings = diagnosis.findings();
        Set<String> keys = findings.stream().map(Finding::key).collect(Collectors.toSet());
        if (!rule.shouldTell(last, clock.getAsLong(), state, keys)) {
            return;
        }
        String trigger;
        Attribution busy;
        if (state == LagRule.State.SPIKE) {
            Spike worst = spikes.stream().max((a, b) -> Double.compare(a.millis(), b.millis())).orElseThrow();
            trigger = String.format(java.util.Locale.ROOT, "one tick took %.0f ms", worst.millis());
            busy = samples.between(worst.startedNanos(), worst.endedNanos());
        } else {
            trigger = "the server has been lagging for a while";
            busy = samples.all();
        }
        Report report = diagnosis.report(trigger, state, busy, findings);
        last = new NoticeRule.Last(clock.getAsLong(), state, keys);
        Scheduling.async(plugin, () -> store.add(report));
        notifier.everyone(report);
    }

    /** While it lags, the chunks are looked at once a minute anyway, for something new. */
    private boolean lookAgain(LagRule.State state) {
        return (state == LagRule.State.LAGGING || state == LagRule.State.SPIKE)
                && clock.getAsLong() - last.at() >= Duration.ofMinutes(1).toMillis();
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public String describe() {
        return "looks at the tick every five seconds and reports lag";
    }
}
