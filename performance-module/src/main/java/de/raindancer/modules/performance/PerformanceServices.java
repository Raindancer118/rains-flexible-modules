package de.raindancer.modules.performance;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.performance.model.SampleRing;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.rules.FarmRule;
import de.raindancer.modules.performance.service.Diagnosis;
import de.raindancer.modules.performance.service.FarmCounter;
import de.raindancer.modules.performance.service.FixService;
import de.raindancer.modules.performance.service.Notifier;
import de.raindancer.modules.performance.store.ReportStore;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What this module built, handed to its commands and listeners. */
public record PerformanceServices(
        Plugin plugin,
        Server server,
        Messages messages,
        Supplier<PerformanceSettings> settings,
        FarmRule rule,
        FarmCounter counter,
        TickWindow window,
        SampleRing samples,
        Diagnosis diagnosis,
        ReportStore reports,
        FixService fixes,
        Notifier notifier) {
}
