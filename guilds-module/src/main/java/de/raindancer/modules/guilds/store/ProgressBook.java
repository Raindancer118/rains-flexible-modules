package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Standing;
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
    private final Map<String, Standing> standings = new HashMap<>();
    private final Map<String, Map<Long, Long>> weekXp = new HashMap<>();
    private ProgressStorage.CrownState crown = ProgressStorage.CrownState.NONE;
    private final Set<String> standingsDirty = new LinkedHashSet<>();
    private final Set<String> weeksDirty = new LinkedHashSet<>();
    private final Set<String> honoursForgotten = new LinkedHashSet<>();
    private boolean crownDirty;
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
        standings.putAll(loaded.get().standings());
        loaded.get().weekXp().forEach((guild, weeks) -> weekXp.put(guild, new HashMap<>(weeks)));
        crown = loaded.get().crown();
    }

    public boolean readable() {
        return readable;
    }

    // ------------------------------------------------------------------ experience by source

    public synchronized void add(String guild, XpSource source, long xp) {
        add(guild, source, xp, Long.MIN_VALUE);
    }

    /** Counts experience by source, and towards the guild's total for {@code week} — the crown's race. */
    public synchronized void add(String guild, XpSource source, long xp, long week) {
        if (xp <= 0) {
            return;
        }
        totals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class))
                .merge(source, xp, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
        totalsDirty.add(guild);
        if (week != Long.MIN_VALUE && source != XpSource.STAFF) {
            Map<Long, Long> weeks = weekXp.computeIfAbsent(guild, key -> new HashMap<>());
            weeks.merge(week, xp, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
            // Two weeks are all the crown ever looks at: this one and the one being settled.
            weeks.keySet().removeIf(each -> each < week - 1);
            weeksDirty.add(guild);
        }
    }

    /** What each guild earned in {@code week}. */
    public synchronized Map<String, Long> weekXp(long week) {
        Map<String, Long> found = new HashMap<>();
        weekXp.forEach((guild, weeks) -> {
            Long xp = weeks.get(week);
            if (xp != null) {
                found.put(guild, xp);
            }
        });
        return found;
    }

    // ------------------------------------------------------------------ standing: perks, prestige, crowns

    /**
     * The guild's standing; one never written is made from {@code levelPointsSoFar} — a guild that levelled before
     * perks existed starts with a point for every level it already has.
     */
    public synchronized Standing standing(String guild, java.util.function.IntSupplier levelPointsSoFar) {
        Standing found = standings.get(guild);
        if (found == null) {
            found = new Standing(levelPointsSoFar.getAsInt(), 0, 0, Map.of(), 0);
            standings.put(guild, found);
            standingsDirty.add(guild);
        }
        return found;
    }

    /** Changes a standing in one step; {@code change} may answer the same standing to change nothing. */
    public synchronized Standing standing(String guild, java.util.function.IntSupplier levelPointsSoFar,
                                          java.util.function.UnaryOperator<Standing> change) {
        Standing before = standing(guild, levelPointsSoFar);
        Standing after = change.apply(before);
        if (after != null && !after.equals(before)) {
            standings.put(guild, after);
            standingsDirty.add(guild);
            return after;
        }
        return before;
    }

    public synchronized ProgressStorage.CrownState crown() {
        return crown;
    }

    public synchronized void crown(ProgressStorage.CrownState next) {
        crown = next == null ? ProgressStorage.CrownState.NONE : next;
        crownDirty = true;
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
        standings.remove(guild);
        weekXp.remove(guild);
        standingsDirty.remove(guild);
        weeksDirty.remove(guild);
        honoursForgotten.add(guild);
        if (crown.guild().equals(guild)) {
            crown = new ProgressStorage.CrownState("", crown.week(), crown.settledWeek());
            crownDirty = true;
        }
    }

    // ------------------------------------------------------------------ writing

    public synchronized boolean hasUnwritten() {
        return !totalsDirty.isEmpty() || !contractsDirty.isEmpty() || !forgotten.isEmpty() || boostDirty
                || !standingsDirty.isEmpty() || !weeksDirty.isEmpty() || !honoursForgotten.isEmpty() || crownDirty;
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
            Map<String, Standing> writeStandings = new HashMap<>();
            Map<String, Map<Long, Long>> writeWeeks = new HashMap<>();
            Set<String> writeHonoursForgotten;
            Optional<ProgressStorage.CrownState> writeCrown;
            synchronized (this) {
                standingsDirty.forEach(guild -> {
                    Standing standing = standings.get(guild);
                    if (standing != null) {
                        writeStandings.put(guild, standing);
                    }
                });
                weeksDirty.forEach(guild -> writeWeeks.put(guild, Map.copyOf(weekXp.getOrDefault(guild, Map.of()))));
                writeHonoursForgotten = Set.copyOf(honoursForgotten);
                writeCrown = crownDirty ? Optional.of(crown) : Optional.empty();
                standingsDirty.clear();
                weeksDirty.clear();
                honoursForgotten.clear();
                crownDirty = false;
            }
            boolean written = storage.write(writeTotals, writeContracts, writeForgotten, writeBoost);
            boolean honoured = storage.writeHonours(writeStandings, writeWeeks, writeHonoursForgotten, writeCrown);
            synchronized (this) {
                if (!written) {
                    writeTotals.keySet().stream().filter(totals::containsKey).forEach(totalsDirty::add);
                    writeContracts.keySet().stream().filter(contracts::containsKey).forEach(contractsDirty::add);
                    writeForgotten.stream().filter(guild -> !totals.containsKey(guild)).forEach(forgotten::add);
                    boostDirty |= writeBoost.isPresent();
                }
                if (!honoured) {
                    writeStandings.keySet().stream().filter(standings::containsKey).forEach(standingsDirty::add);
                    writeWeeks.keySet().stream().filter(weekXp::containsKey).forEach(weeksDirty::add);
                    writeHonoursForgotten.stream().filter(guild -> !standings.containsKey(guild)).forEach(honoursForgotten::add);
                    crownDirty |= writeCrown.isPresent();
                }
            }
            return written && honoured;
        }
    }
}
