package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.XpSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Where a guild's experience came from, its contracts for the week, and the server's boost — in memory, written
 * behind like {@link GuildBook} and with the same promises: a failed write is tried again, and nothing is written
 * over a database that could not be read.
 *
 * <p>Completing a contract is decided here, under the lock, so two members finishing it in the same tick cannot
 * both collect the reward.
 */
public final class ProgressBook {

    private final ProgressStorage storage;
    private final Map<String, Map<XpSource, Long>> totals = new HashMap<>();
    private final Map<String, List<Contract>> contracts = new HashMap<>();
    private Boost boost = Boost.NONE;
    private final Set<String> totalsDirty = new LinkedHashSet<>();
    private final Set<String> contractsDirty = new LinkedHashSet<>();
    private final Set<String> forgotten = new LinkedHashSet<>();
    private boolean boostDirty;
    private volatile boolean readable = true;
    private final Object flushing = new Object();

    public ProgressBook(ProgressStorage storage) {
        this.storage = storage;
    }

    public synchronized void load() {
        totals.clear();
        contracts.clear();
        Optional<ProgressStorage.Loaded> loaded = storage.load();
        readable = loaded.isPresent();
        if (loaded.isEmpty()) {
            return;
        }
        loaded.get().totals().forEach((guild, each) -> totals.put(guild, new EnumMap<>(each)));
        for (Contract contract : loaded.get().contracts()) {
            contracts.computeIfAbsent(contract.guild(), key -> new ArrayList<>()).add(contract);
        }
        // Only the newest week of each guild is kept; older rows go with the next write.
        contracts.replaceAll((guild, list) -> {
            long newest = list.stream().mapToLong(Contract::week).max().orElse(0);
            List<Contract> kept = new ArrayList<>(list.stream().filter(each -> each.week() == newest)
                    .sorted(Comparator.comparingInt(Contract::slot)).toList());
            if (kept.size() != list.size()) {
                contractsDirty.add(guild);
            }
            return kept;
        });
        boost = loaded.get().boost();
    }

    public boolean readable() {
        return readable;
    }

    // ------------------------------------------------------------------ experience by source

    public synchronized void add(String guild, XpSource source, long xp) {
        if (xp <= 0) {
            return;
        }
        totals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class))
                .merge(source, xp, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
        totalsDirty.add(guild);
    }

    public synchronized Map<XpSource, Long> totals(String guild) {
        return Map.copyOf(totals.getOrDefault(guild, Map.of()));
    }

    // ------------------------------------------------------------------ contracts

    /** The guild's contracts for {@code week}; dealt by {@code deal} the first time the week is asked for. */
    public synchronized List<Contract> contracts(String guild, long week, Supplier<List<Contract>> deal) {
        List<Contract> now = contracts.get(guild);
        if (now == null || now.isEmpty() || now.getFirst().week() != week) {
            now = new ArrayList<>(deal.get());
            contracts.put(guild, now);
            contractsDirty.add(guild);
        }
        return List.copyOf(now);
    }

    /** Counts an activity towards this week's contracts. @return those it finished, each exactly once */
    public synchronized List<Contract> advance(String guild, long week, Activity activity) {
        List<Contract> now = contracts.get(guild);
        if (now == null || now.isEmpty() || now.getFirst().week() != week) {
            return List.of();
        }
        List<Contract> finished = new ArrayList<>();
        for (int index = 0; index < now.size(); index++) {
            Contract contract = now.get(index);
            if (contract.counts(activity)) {
                Contract after = contract.advanced(activity.amount());
                now.set(index, after);
                contractsDirty.add(guild);
                if (after.done()) {
                    finished.add(after);
                }
            }
        }
        return finished;
    }

    // ------------------------------------------------------------------ the boost

    public synchronized Boost boost() {
        return boost;
    }

    public synchronized void boost(Boost next) {
        boost = next == null ? Boost.NONE : next;
        boostDirty = true;
    }

    /** A disbanded guild's rows go with the next write. */
    public synchronized void forget(String guild) {
        totals.remove(guild);
        contracts.remove(guild);
        totalsDirty.remove(guild);
        contractsDirty.remove(guild);
        forgotten.add(guild);
    }

    // ------------------------------------------------------------------ writing

    public synchronized boolean hasUnwritten() {
        return !totalsDirty.isEmpty() || !contractsDirty.isEmpty() || !forgotten.isEmpty() || boostDirty;
    }

    public boolean flush() {
        synchronized (flushing) {
            Map<String, Map<XpSource, Long>> writeTotals = new HashMap<>();
            Map<String, List<Contract>> writeContracts = new HashMap<>();
            Set<String> writeForgotten;
            Optional<Boost> writeBoost;
            synchronized (this) {
                if (!readable) {
                    return false;
                }
                if (!hasUnwritten()) {
                    return true;
                }
                totalsDirty.forEach(guild -> writeTotals.put(guild, Map.copyOf(totals.getOrDefault(guild, Map.of()))));
                contractsDirty.forEach(guild -> writeContracts.put(guild, List.copyOf(contracts.getOrDefault(guild, List.of()))));
                writeForgotten = Set.copyOf(forgotten);
                writeBoost = boostDirty ? Optional.of(boost) : Optional.empty();
                totalsDirty.clear();
                contractsDirty.clear();
                forgotten.clear();
                boostDirty = false;
            }
            boolean written = storage.write(writeTotals, writeContracts, writeForgotten, writeBoost);
            if (!written) {
                synchronized (this) {
                    writeTotals.keySet().stream().filter(totals::containsKey).forEach(totalsDirty::add);
                    writeContracts.keySet().stream().filter(contracts::containsKey).forEach(contractsDirty::add);
                    writeForgotten.stream().filter(guild -> !totals.containsKey(guild)).forEach(forgotten::add);
                    boostDirty |= writeBoost.isPresent();
                }
            }
            return written;
        }
    }
}
