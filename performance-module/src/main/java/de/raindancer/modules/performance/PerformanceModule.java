package de.raindancer.modules.performance;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.performance.listener.FarmListener;
import de.raindancer.modules.performance.listener.TickListener;
import de.raindancer.modules.performance.model.ClassIndex;
import de.raindancer.modules.performance.model.SampleRing;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.rules.CauseRule;
import de.raindancer.modules.performance.rules.FarmRule;
import de.raindancer.modules.performance.service.Census;
import de.raindancer.modules.performance.service.ClassIndexLoader;
import de.raindancer.modules.performance.service.Diagnosis;
import de.raindancer.modules.performance.service.FarmCounter;
import de.raindancer.modules.performance.service.FixService;
import de.raindancer.modules.performance.service.IPerformanceService;
import de.raindancer.modules.performance.service.LagWatch;
import de.raindancer.modules.performance.service.Notifier;
import de.raindancer.modules.performance.service.Sampler;
import de.raindancer.modules.performance.store.ReportStore;
import de.raindancer.modules.performance.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches the tick, finds what slows it down, proposes fixes and reports to staff; also keeps animal
 * farms from growing past a limit. Written after Lilly's SMP sat at 15 TPS behind a 294-chicken farm
 * nobody knew about until somebody took a stack dump.
 */
public final class PerformanceModule implements FlexModule {

    /** A minute of ticks. */
    private static final int WINDOW_TICKS = 1200;
    /** A minute of samples at the default 20 ms. */
    private static final int SAMPLES_KEPT = 3000;

    private static final ModuleInfo INFO = ModuleInfo.of("performance", "Performance Optimizer", "0.1.0")
            .describedAs("Finds what slows the server down, proposes fixes, reports to staff, and keeps farms in check")
            .by("Raindancer118");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<PerformanceSettings> settings =
                context.settings(PerformanceSettings.class, PerformanceSettings.DEFAULTS);
        Messages messages = context.core().messages();
        messages.defineFrom(PerformanceModule.class.getResourceAsStream("messages.yml"), context.chat().brand()::chatPrefix);
        messages.seriousFrom(PerformanceModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        Plugin plugin = context.plugin();
        Server server = plugin.getServer();
        TickWindow window = new TickWindow(WINDOW_TICKS);
        SampleRing samples = new SampleRing(SAMPLES_KEPT);
        ClassIndex classes = new ClassIndex();
        Scheduling.async(plugin, () -> context.log().info("Read the classes of {} plugin(s), to say which one a slow tick was spent in.",
                ClassIndexLoader.fill(classes, server.getPluginManager().getPlugins())));
        ReportStore store = new ReportStore(context.dataFolder().resolve("reports"), () -> settings.current().reportsKept());
        Diagnosis diagnosis = new Diagnosis(server, context.core(), new Census(), window, store, settings::current);
        FixService fixes = new FixService(plugin, server, messages, context.log());
        Notifier notifier = new Notifier(plugin, server, messages, context.log());
        List<IPerformanceService> all = new ArrayList<>(List.of(diagnosis, fixes, notifier));

        PerformanceServices services = new PerformanceServices(plugin, server, messages, settings::current,
                new FarmRule(), new FarmCounter(), window, samples, diagnosis, store, fixes, notifier);
        context.listener(new FarmListener(services));

        if (Scheduling.isFolia()) {
            // No single server thread to sample or time, and no thread that may read every world.
            context.log().info("On Folia the lag watch is off; farm limits work as everywhere.");
        } else {
            Sampler sampler = new Sampler(samples, new CauseRule(classes::pluginOf), () -> settings.current().sampleEveryMs());
            TickListener ticks = new TickListener(window, sampler, () -> settings.current().spikeMs());
            context.listener(ticks);
            sampler.start();
            context.closeWith(sampler);
            LagWatch watch = new LagWatch(plugin, window, samples, ticks, diagnosis, store, notifier,
                    System::currentTimeMillis, settings.current());
            watch.start();
            context.closeWith(watch);
            all.add(watch);
        }
        settings.onChange(fresh -> all.forEach(service -> service.settings(fresh)));
        PerformanceCommands.ready(services);

        PerformanceSettings now = settings.current();
        context.log().info("Lag watch {}; farm limits {}: {} of one kind, {} animals within {} blocks.",
                now.watch() ? "on" : "off", now.enabled() ? "on" : "off", now.mostOfOneKind(), now.mostAnimals(), now.radius());
    }

    @Override
    public List<ModuleCommand> commands() {
        return PerformanceCommands.declared();
    }

    @Override
    public void disable() {
        PerformanceCommands.stopped();
    }
}
