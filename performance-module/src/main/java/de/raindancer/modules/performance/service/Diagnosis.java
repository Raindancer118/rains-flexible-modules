package de.raindancer.modules.performance.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.world.protection.ProtectedArea;
import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.ChunkCensus;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;
import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.rules.AdviceRule;
import de.raindancer.modules.performance.rules.FindingRule;
import de.raindancer.modules.performance.rules.LagRule;
import de.raindancer.modules.performance.store.ReportStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Puts a report together: the tick window, what the samples say, what the chunks hold, whose land it
 * is. Reads every world — call on the global region, which on Paper is the server thread.
 */
public final class Diagnosis implements IPerformanceService {

    private final Server server;
    private final RainsCore core;
    private final Census census;
    private final TickWindow window;
    private final ReportStore store;
    private volatile PerformanceSettings settings;

    public Diagnosis(Server server, RainsCore core, Census census, TickWindow window, ReportStore store,
                     Supplier<PerformanceSettings> settings) {
        this.server = server;
        this.core = core;
        this.census = census;
        this.window = window;
        this.store = store;
        this.settings = settings.get();
    }

    @Override
    public void settings(PerformanceSettings changed) {
        this.settings = changed;
    }

    /** The findings as they stand — a census of every loaded chunk, judged. */
    public List<Finding> findings() {
        PerformanceSettings now = settings;
        List<Finding> found = new FindingRule(now.limits()).findings(census.take(server.getWorlds()), now.farmLimit());
        List<Finding> owned = new ArrayList<>(found.size());
        for (Finding finding : found) {
            owned.add(withOwner(finding));
        }
        return owned;
    }

    public Report report(String trigger, LagRule.State state, Attribution busy, List<Finding> findings) {
        PerformanceSettings now = settings;
        int distance = simulationDistance();
        List<Fix> advice = new AdviceRule(now.lowestSimulationDistance(), now.suspectPercent())
                .advice(busy, state, distance);
        return new Report(store.nextNumber(), Instant.now(), trigger, state, window.mean(), window.percentile95(),
                window.worst(), window.tps(), distance, busy.ranked(), findings, advice);
    }

    /** The overworld's, or the first world's — what a server-wide suggestion starts from. */
    public int simulationDistance() {
        World first = server.getWorlds().isEmpty() ? null : server.getWorlds().getFirst();
        return first == null ? server.getSimulationDistance() : first.getSimulationDistance();
    }

    private Finding withOwner(Finding finding) {
        ChunkCensus where = finding.where();
        World world = worldOf(where.world());
        if (world == null) {
            return finding;
        }
        double[] spot = where.spotOf(finding.what());
        Optional<ProtectedArea> area = core.land().areaAt(new Location(world, spot[0], spot[1], spot[2]));
        if (area.isEmpty()) {
            return finding;
        }
        List<String> names = new ArrayList<>();
        for (UUID owner : area.get().owners()) {
            OfflinePlayer player = server.getOfflinePlayer(owner);
            names.add(player.getName() != null ? player.getName() : owner.toString());
        }
        return finding.on(area.get().name(), area.get().owners(), names);
    }

    public World worldOf(String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        return parsed == null ? null : server.getWorld(parsed);
    }

    @Override
    public String describe() {
        return "puts together a report from the tick, the samples and a census of the loaded chunks";
    }
}
