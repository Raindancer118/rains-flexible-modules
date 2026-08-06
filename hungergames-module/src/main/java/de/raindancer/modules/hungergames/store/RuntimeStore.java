package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persists {@code runtime.yml}: the two facts about a round that would otherwise cause a restart to do
 * something twice.
 *
 * <h2>What is here, and what deliberately is not</h2>
 * The op snapshot — who was OP before being de-opped for the round, so a restart mid-round does not leave
 * an admin permanently stripped of it — and the schedule marks for supply drops and sponsor-token waves,
 * which stop a timetable that has already fired from firing again the moment the server comes back. Both
 * are the same shape of problem: {@code model.Schedule} and {@code model.TokenSchedule} are pure functions
 * of elapsed time, so replaying them after a restart is only safe if the caller's "have I already acted on
 * this" bit survived the restart too. That bit is what lives here.
 *
 * <p>The arena's geometry and anything phrased as a {@code World} or a {@code Location} is <em>not</em>
 * here, and not by omission: this package may not import a Bukkit server type, because a store has to be
 * exercised in a test with no server running. Restoring where things are in the world is a service's job,
 * against a loaded world, not this store's.
 *
 * <h2>Why a corrupt file is quarantined before the next section is ever written</h2>
 * The op snapshot, the drop marks and the token marks all live in one file, and each is saved
 * independently — a supply drop firing does not wait for a sponsor-token wave to also be due. If the file
 * could not be parsed, writing straight over it the next time any one of those three things changed would
 * destroy the other two sections along with whatever caused the corruption in the first place. So a load
 * that finds the file unreadable moves it aside via {@link YamlStore#quarantine()} there and then, and
 * every save after that starts a clean file rather than silently discarding one that could have been
 * recovered by hand.
 */
public final class RuntimeStore {

    private static final LogChannel log = Log.of("hungergames");

    /** Which supply drops have already fired, and which have fired but not yet landed. */
    public record SupplyDropState(Set<Integer> triggeredIndices, List<String> pendingLandings) {
        public SupplyDropState {
            triggeredIndices = Set.copyOf(triggeredIndices);
            pendingLandings = List.copyOf(pendingLandings);
        }

        public static SupplyDropState empty() {
            return new SupplyDropState(Set.of(), List.of());
        }
    }

    /** How many sponsor-token waves a player has already been paid for, and how many tokens that came to. */
    public record TokenState(int wavesReceived, int tokensEarned) {
    }

    /**
     * Where the deathmatch stands, for a restart to pick back up rather than lose or repeat it.
     *
     * <p>{@code ACTIVE} needs nothing more than the phase itself: Vanilla already carries a running border
     * transition across a restart in {@code level.dat}, so all a service needs telling is "do not let the
     * ordinary border phases fire again" — see {@code service.DeathmatchService}'s class note. {@code
     * WARNING} additionally needs {@code warningStartedAtMillis}, because a countdown with no clock running
     * is not a countdown: without the moment it started, a restart could only restart the full warning from
     * the top (giving tributes a second, unearned reprieve) or fire immediately (giving them none at all).
     *
     * @param warningStartedAtMillis epoch milliseconds the warning began; meaningless outside {@code WARNING}
     */
    public record DeathmatchState(Phase phase, long warningStartedAtMillis) {

        public enum Phase {
            OFF,
            WARNING,
            ACTIVE
        }

        public static DeathmatchState off() {
            return new DeathmatchState(Phase.OFF, 0L);
        }
    }

    private final YamlStore store;
    private final List<String> problems = new ArrayList<>();

    public RuntimeStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What could not be read the last time this store looked at the file. Empty when it was clean. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    // ==================== op snapshot ====================

    /** Records which tributes were OP before being de-opped for the round. */
    public void saveOpSnapshot(Set<UUID> deoppedAdmins) {
        safeUpdate(yaml -> yaml.set("deopped-admins",
                deoppedAdmins.stream().map(UUID::toString).sorted().toList()));
    }

    public Set<UUID> loadOpSnapshot() {
        YamlConfiguration yaml = safeRead();
        Set<UUID> result = new LinkedHashSet<>();
        for (String raw : yaml.getStringList("deopped-admins")) {
            try {
                result.add(UUID.fromString(raw));
            } catch (IllegalArgumentException broken) {
                note("an entry in the OP snapshot was skipped (" + broken.getMessage() + ")");
            }
        }
        return result;
    }

    // ==================== supply-drop schedule marks ====================

    /** Records which drops have fired and which are still airborne, so a restart triggers neither twice. */
    public void saveSupplyDropState(SupplyDropState state) {
        safeUpdate(yaml -> {
            yaml.set("supply-drops.triggered", state.triggeredIndices().stream().sorted().toList());
            yaml.set("supply-drops.pending", state.pendingLandings().isEmpty() ? null : state.pendingLandings());
        });
    }

    public SupplyDropState loadSupplyDropState() {
        YamlConfiguration yaml = safeRead();
        Set<Integer> triggered = new LinkedHashSet<>(yaml.getIntegerList("supply-drops.triggered"));
        List<String> pending = yaml.getStringList("supply-drops.pending");
        return new SupplyDropState(triggered, pending);
    }

    // ==================== sponsor-token schedule marks ====================

    /** Records the waves each player has been paid for, so a rejoin is topped up rather than paid twice. */
    public void saveTokenState(Map<UUID, TokenState> perPlayer) {
        safeUpdate(yaml -> {
            yaml.set("sponsor-tokens", null);
            perPlayer.forEach((uuid, state) -> {
                String path = "sponsor-tokens." + uuid;
                yaml.set(path + ".waves", state.wavesReceived());
                yaml.set(path + ".earned", state.tokensEarned());
            });
        });
    }

    public Map<UUID, TokenState> loadTokenState() {
        YamlConfiguration yaml = safeRead();
        Map<UUID, TokenState> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("sponsor-tokens");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            try {
                result.put(UUID.fromString(key), new TokenState(
                        section.getInt(key + ".waves", 0),
                        section.getInt(key + ".earned", 0)));
            } catch (IllegalArgumentException broken) {
                note("the sponsor-token entry for '" + key + "' was skipped (" + broken.getMessage() + ")");
            }
        }
        return result;
    }

    // ==================== sponsor-beacon state ====================

    /**
     * Which sponsor beacons are currently standing, and which random-timed spawn slots have already
     * fired — the same shape of restart problem as {@link SupplyDropState}, for the same reason: a beacon
     * is a fact about the world, not something safely re-derived from a timetable after a restart, and a
     * spawn slot that already fired must not spawn a second beacon the moment the server comes back.
     */
    public record SponsorBeaconState(List<String> locations, Set<Integer> triggeredSpawns) {
        public SponsorBeaconState {
            locations = List.copyOf(locations);
            triggeredSpawns = Set.copyOf(triggeredSpawns);
        }

        public static SponsorBeaconState empty() {
            return new SponsorBeaconState(List.of(), Set.of());
        }
    }

    /** Records which beacons are active and which spawn slots have fired, so a restart loses neither. */
    public void saveSponsorBeaconState(SponsorBeaconState state) {
        safeUpdate(yaml -> {
            yaml.set("sponsor-beacons.locations", state.locations().isEmpty() ? null : state.locations());
            yaml.set("sponsor-beacons.triggered-spawns",
                    state.triggeredSpawns().isEmpty() ? null : state.triggeredSpawns().stream().sorted().toList());
        });
    }

    public SponsorBeaconState loadSponsorBeaconState() {
        YamlConfiguration yaml = safeRead();
        List<String> locations = yaml.getStringList("sponsor-beacons.locations");
        Set<Integer> triggered = new LinkedHashSet<>(yaml.getIntegerList("sponsor-beacons.triggered-spawns"));
        return new SponsorBeaconState(locations, triggered);
    }

    // ==================== deathmatch state ====================

    /** Records where the deathmatch stands, so a restart can resume it — see {@link DeathmatchState}. */
    public void saveDeathmatchState(DeathmatchState state) {
        safeUpdate(yaml -> {
            if (state.phase() == DeathmatchState.Phase.OFF) {
                // Written as absent rather than as the literal string "OFF", so a file that has never
                // seen a deathmatch stays free of a deathmatch section entirely — nothing here to explain
                // to somebody reading the file by hand for a round that had none.
                yaml.set("deathmatch", null);
            } else {
                yaml.set("deathmatch.phase", state.phase().name());
                yaml.set("deathmatch.warning-started-at", state.warningStartedAtMillis());
            }
        });
    }

    public DeathmatchState loadDeathmatchState() {
        YamlConfiguration yaml = safeRead();
        String raw = yaml.getString("deathmatch.phase");
        if (raw == null) {
            return DeathmatchState.off();
        }
        try {
            return new DeathmatchState(DeathmatchState.Phase.valueOf(raw),
                    yaml.getLong("deathmatch.warning-started-at", 0L));
        } catch (IllegalArgumentException broken) {
            note("the deathmatch phase '" + raw + "' was not recognised — treated as OFF");
            return DeathmatchState.off();
        }
    }

    // ==================== whole file ====================

    /** Removes the entire runtime state (round end, full reset). */
    public void clear() {
        try {
            java.nio.file.Files.deleteIfExists(store.file());
        } catch (java.io.IOException failure) {
            log.warn("runtime.yml could not be deleted: {}", failure.getMessage());
        }
    }

    // ==================== intern ====================

    /**
     * Reads the file, or an empty configuration when it does not exist or could not be parsed. A read
     * failure quarantines the file immediately, so the very next {@link #safeUpdate} starts fresh rather
     * than eventually writing over it.
     */
    private YamlConfiguration safeRead() {
        synchronized (problems) {
            problems.clear();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return new YamlConfiguration();
        }
        return yaml;
    }

    /**
     * Reads the current file (quarantining it first if it will not parse), lets the caller change one
     * section, and writes the result back whole. The three sections share a file but never a save, so this
     * is what stops the drop marks from being erased by a save that only meant to touch the token marks.
     */
    private void safeUpdate(Consumer<YamlConfiguration> change) {
        YamlConfiguration current = safeRead();
        store.write(yaml -> {
            copy(current, yaml);
            change.accept(yaml);
        });
    }

    private static void copy(YamlConfiguration from, YamlConfiguration to) {
        for (String key : from.getKeys(true)) {
            if (!from.isConfigurationSection(key)) {
                to.set(key, from.get(key));
            }
        }
    }

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("runtime.yml: {}", problem);
    }
}
