package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.permission.Grants;
import de.raindancer.modules.moderation.model.StaffRank;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is staff, and at what rank.
 *
 * <h2>Why the rank is stored when the permissions are the real power</h2>
 * The rank is the thing a person says out loud — "she's a moderator" — and the nodes are the thing the
 * server acts on. Keeping only the nodes means nobody can answer "what rank is she?" without comparing
 * thirteen booleans against four presets. Keeping only the rank means the per-person toggles cannot
 * exist at all.
 *
 * <p>So both: this owns the label, Core's {@link Grants} owns the power, and this can say when the two
 * have drifted — which is exactly what a helper with one extra node is. {@link #extraNodes} and
 * {@link #missingNodes} are what let a screen say "Moderator, and one extra" rather than quietly showing
 * her as an ordinary moderator for the next three months.
 *
 * <h2>Why a toggle only accepts a node some rank grants</h2>
 * Because the alternative is a typo scattering nodes into the grants file that no screen lists and
 * nothing can take away again. See {@link StaffRank#everyGrantableNode()}.
 */
public final class StaffRoster {

    private static final LogChannel log = Log.of("moderation");

    private final Map<UUID, StaffRank> ranks = new ConcurrentHashMap<>();
    private final Grants grants;
    private final YamlStore store;

    public StaffRoster(Path dataFolder, Grants grants) {
        this.grants = grants;
        this.store = new YamlStore(dataFolder.resolve("staff.yml"));
    }

    /** Where the roster is kept — for a diagnostic, and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    // ---------------------------------------------------------------------------- the ladder

    /**
     * Makes somebody staff at this rank, replacing whatever they had.
     *
     * <p>Replaces rather than adds. A demotion is a promotion downwards, and the version that only
     * added nodes made every demotion a no-op — the dangerous direction, because nobody notices a
     * demotion that did not happen until the person uses the power they should have lost.
     *
     * @return whether anything changed
     */
    public boolean promote(UUID who, StaffRank rank) {
        if (who == null || rank == null) {
            return false;
        }
        ranks.put(who, rank);
        grants.set(who, rank.nodes());
        return true;
    }

    /**
     * Takes the rank away, and every node with it.
     *
     * @return whether they were staff at all
     */
    public boolean demote(UUID who) {
        if (who == null || ranks.remove(who) == null) {
            return false;
        }
        // Everything, including immunity. A demoted admin who keeps it is an account nobody can act on
        // and nobody meant to protect.
        grants.clear(who);
        return true;
    }

    /** Their rank, if they are staff. */
    public Optional<StaffRank> rankOf(UUID who) {
        return who == null ? Optional.empty() : Optional.ofNullable(ranks.get(who));
    }

    public boolean isStaff(UUID who) {
        return who != null && ranks.containsKey(who);
    }

    /** Everybody with a rank. */
    public Set<UUID> everybody() {
        return Set.copyOf(ranks.keySet());
    }

    /** Everybody at exactly this rank. */
    public List<UUID> ofRank(StaffRank rank) {
        List<UUID> found = new ArrayList<>();
        ranks.forEach((who, theirs) -> {
            if (theirs == rank) {
                found.add(who);
            }
        });
        return found;
    }

    public int size() {
        return ranks.size();
    }

    // ---------------------------------------------------------------------------- one node at a time

    /**
     * Turns one node on or off for one person, leaving their rank alone.
     *
     * <p>The exception the presets exist to make rare rather than impossible: one helper who is trusted
     * to ban, without inventing a fifth tier for her.
     *
     * @return whether they now hold it
     */
    public boolean toggle(UUID who, String node) {
        if (!isStaff(who) || node == null || !StaffRank.everyGrantableNode().contains(node)) {
            return false;
        }
        if (grants.has(who, node)) {
            grants.revoke(who, node);
            return false;
        }
        grants.grant(who, node);
        return true;
    }

    /** Puts a drifted person back to exactly what their rank grants. */
    public boolean reapplyPreset(UUID who) {
        return rankOf(who).map(rank -> promote(who, rank)).orElse(false);
    }

    /** Whether their permissions are exactly what their rank grants. */
    public boolean matchesPreset(UUID who) {
        return extraNodes(who).isEmpty() && missingNodes(who).isEmpty();
    }

    /** What they hold beyond their rank — hand-granted at some point, by somebody. */
    public List<String> extraNodes(UUID who) {
        Optional<StaffRank> rank = rankOf(who);
        if (rank.isEmpty()) {
            return List.of();
        }
        List<String> extra = new ArrayList<>(grants.nodesFor(who));
        extra.removeAll(rank.get().nodes());
        extra.sort(String::compareTo);
        return extra;
    }

    /** What their rank grants and they do not have — taken away at some point, by somebody. */
    public List<String> missingNodes(UUID who) {
        Optional<StaffRank> rank = rankOf(who);
        if (rank.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>(rank.get().nodes());
        missing.removeAll(grants.nodesFor(who));
        missing.sort(String::compareTo);
        return missing;
    }

    // ---------------------------------------------------------------------------- persistence

    /**
     * Reads the roster.
     *
     * <p>Does <b>not</b> re-apply the presets. The grants file is the authority on what somebody
     * actually holds, and re-applying here would silently undo every deliberate per-person toggle on
     * every restart.
     */
    public void load() {
        ranks.clear();
        var root = store.read().getConfigurationSection("staff");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            Optional<StaffRank> rank = StaffRank.byName(root.getString(id));
            if (rank.isEmpty()) {
                unreadable.add(id + " (" + root.getString(id) + ")");
                continue;
            }
            ranks.put(who, rank.get());
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in staff.yml could not be read and have been skipped: {}. "
                            + "Anybody they named is not staff this session — their granted "
                            + "permissions are untouched, so check grants.yml as well.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    /** Writes the roster. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> ranks.forEach((who, rank) ->
                yaml.set("staff." + who, rank.key())));
    }
}
