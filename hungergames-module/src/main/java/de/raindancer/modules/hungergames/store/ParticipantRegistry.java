package de.raindancer.modules.hungergames.store;

import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.modules.hungergames.model.SessionSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The round's tribute roster. UUID is the only identity — see {@code model.Participant}'s class note; a
 * name is a caption that may be stale, never a key.
 *
 * <h2>Online status is not part of the state</h2>
 * Nothing here has a concept of a disconnect. A tribute's {@link ParticipantState} does not move when
 * they leave, only when something eliminates them — the invariant
 * {@code rules.WinnerRule} is written against.
 *
 * <h2>Team membership is asked for, never stored twice</h2>
 * Core's {@code de.raindancer.core.social.team.Teams}, held by {@code store.GameSession}, is the one place
 * that knows who is on which team. Whenever this registry
 * builds a {@link Participant} snapshot it enriches it through the injected {@code teamLookup} function
 * rather than keeping its own copy — a second copy is a copy that can disagree with the first, and a
 * tribute shown on the wrong team in a scoreboard is a bug nobody can reproduce because it depends on
 * which of the two copies happened to be read.
 */
public final class ParticipantRegistry {

    private record Entry(String name, ParticipantState state) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Function<UUID, Optional<TeamId>> teamLookup;

    /**
     * @param teamLookup returns a player's current team, typically {@code teamRegistry::teamIdOf}
     */
    public ParticipantRegistry(Function<UUID, Optional<TeamId>> teamLookup) {
        this.teamLookup = teamLookup;
    }

    /** Registers a tribute (whitelist entry). */
    public boolean add(UUID uuid, String name) {
        if (entries.containsKey(uuid)) {
            return false;
        }
        entries.put(uuid, new Entry(name, ParticipantState.ALIVE));
        return true;
    }

    /** Removes a tribute completely (whitelist entry withdrawn). */
    public boolean remove(UUID uuid) {
        return entries.remove(uuid) != null;
    }

    public boolean contains(UUID uuid) {
        return entries.containsKey(uuid);
    }

    /** Refreshes the display name (e.g. on join). */
    public void updateName(UUID uuid, String name) {
        Entry entry = entries.get(uuid);
        if (entry != null && !entry.name().equals(name)) {
            entries.put(uuid, new Entry(name, entry.state()));
        }
    }

    /**
     * Marks a tribute eliminated.
     *
     * @return {@code false} if unknown, or already eliminated
     */
    public boolean eliminate(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry == null || entry.state() == ParticipantState.ELIMINATED) {
            return false;
        }
        entries.put(uuid, new Entry(entry.name(), ParticipantState.ELIMINATED));
        return true;
    }

    /** Undoes an elimination (an admin correction). */
    public boolean revive(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry == null || entry.state() == ParticipantState.ALIVE) {
            return false;
        }
        entries.put(uuid, new Entry(entry.name(), ParticipantState.ALIVE));
        return true;
    }

    public boolean isAlive(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry != null && entry.state() == ParticipantState.ALIVE;
    }

    public Optional<Participant> get(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(toParticipant(uuid, entry));
    }

    public Optional<String> nameOf(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? Optional.empty() : Optional.of(entry.name());
    }

    /** A snapshot of every tribute. */
    public Collection<Participant> all() {
        return entries.entrySet().stream()
                .map(e -> toParticipant(e.getKey(), e.getValue()))
                .toList();
    }

    /** UUIDs of everybody alive (online or not). */
    public Set<UUID> alive() {
        return byState(ParticipantState.ALIVE);
    }

    /** UUIDs of everybody eliminated. */
    public Set<UUID> eliminated() {
        return byState(ParticipantState.ELIMINATED);
    }

    public int aliveCount() {
        return alive().size();
    }

    /** Resets every elimination (a new round; the whitelist itself is kept). */
    public void resetStates() {
        entries.replaceAll((uuid, entry) -> new Entry(entry.name(), ParticipantState.ALIVE));
    }

    /** Empties the registry entirely. */
    public void clear() {
        entries.clear();
    }

    /** Restores a saved state (session restore). */
    public void restore(Collection<SessionSnapshot.ParticipantData> saved) {
        entries.clear();
        for (SessionSnapshot.ParticipantData data : saved) {
            entries.put(data.uuid(), new Entry(data.name(), data.state()));
        }
    }

    private Set<UUID> byState(ParticipantState state) {
        Set<UUID> result = new LinkedHashSet<>();
        entries.forEach((uuid, entry) -> {
            if (entry.state() == state) {
                result.add(uuid);
            }
        });
        return Set.copyOf(result);
    }

    private Participant toParticipant(UUID uuid, Entry entry) {
        return new Participant(uuid, entry.name(), entry.state(), teamLookup.apply(uuid));
    }
}
