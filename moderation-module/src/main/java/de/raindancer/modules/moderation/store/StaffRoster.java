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

    /**
     * Nodes an admin has individually taken away from somebody, remembered so {@link #topUpFromPreset}
     * knows not to hand them straight back.
     *
     * <h2>Why this has to be its own list rather than just "absent from grants"</h2>
     * A node missing from somebody's grants means one of two completely different things: an admin
     * took it away on purpose, or they were promoted before the preset grew it and simply never
     * received it. {@link Grants} cannot tell those apart — a node is either held or it is not, with
     * nothing to say which of the two stories is true — so without this, {@link #topUpFromPreset}
     * could not tell "add what is new" from "restore what was refused" and had no choice but to do
     * both, which is exactly the bug this list exists to prevent: a permission explicitly revoked
     * quietly reappearing the next time its owner logs in.
     *
     * <p>Cleared whenever {@link #promote} runs — a fresh rank, including a re-application of the same
     * one through {@link #reapplyPreset}, is a fresh start, not a set of exclusions carried over from
     * whatever rank they held a moment before.
     */
    private final Map<UUID, Set<String>> denied = new ConcurrentHashMap<>();

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
        // A fresh assignment of the rank's full node set already includes whatever was individually
        // refused before — carrying the exclusion forward would have topUpFromPreset immediately
        // undo half of what this line just did.
        denied.remove(who);
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
        denied.remove(who);
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
            // Marked, so a later join does not read the absence as "never got around to granting
            // this" and hand it straight back — see the field note on denied.
            deniedFor(who).add(node);
            return false;
        }
        grants.grant(who, node);
        // Granting it by hand is exactly as deliberate as revoking it was, and un-marks whatever
        // refusal came before — a node given back on purpose is not still "denied".
        undeny(who, node);
        return true;
    }

    /**
     * Puts a drifted person back to exactly what their rank grants — both what is missing and, just as
     * much, what is not. The deliberate full reset behind "Put the preset back": every individual
     * toggle, in either direction, is undone.
     */
    public boolean reapplyPreset(UUID who) {
        return rankOf(who).map(rank -> promote(who, rank)).orElse(false);
    }

    /**
     * Grants whatever the rank has gained since somebody was last given it — and only that. A node an
     * admin individually {@link #toggle}d off stays off, because it is in {@link #denied} rather than
     * merely absent; this never removes anything either, so it is safe to call on every join rather
     * than only when a screen asks for a deliberate reset. See {@code StaffService#topUpOnJoin}, its
     * one caller.
     *
     * @return whether anything was actually new
     */
    public boolean topUpFromPreset(UUID who) {
        return rankOf(who).map(rank -> {
            Set<String> excluded = denied.getOrDefault(who, Set.of());
            List<String> due = new ArrayList<>(rank.nodes());
            due.removeAll(excluded);
            return grants.grantAll(who, due);
        }).orElse(false);
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
        denied.clear();
        var yaml = store.read();
        var root = yaml.getConfigurationSection("staff");
        if (root != null) {
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
        var deniedRoot = yaml.getConfigurationSection("denied");
        if (deniedRoot != null) {
            for (String id : deniedRoot.getKeys(false)) {
                try {
                    UUID who = UUID.fromString(id);
                    List<String> nodes = deniedRoot.getStringList(id);
                    if (!nodes.isEmpty()) {
                        deniedFor(who).addAll(nodes);
                    }
                } catch (IllegalArgumentException notAnId) {
                    // Already logged loudly above for the ranks that fail the same way. Losing an
                    // entry here means a future top-up might hand back a node this one was refused —
                    // not silent data loss, since grants.yml still says what they actually hold.
                }
            }
        }
    }

    /** Writes the roster. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> {
            ranks.forEach((who, rank) -> yaml.set("staff." + who, rank.key()));
            denied.forEach((who, nodes) -> {
                if (!nodes.isEmpty()) {
                    yaml.set("denied." + who, new ArrayList<>(nodes));
                }
            });
        });
    }

    // ---------------------------------------------------------------------------- internals

    private Set<String> deniedFor(UUID who) {
        return denied.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet());
    }

    /** Un-marks one node, dropping the entry entirely once nothing is left in it. */
    private void undeny(UUID who, String node) {
        Set<String> theirs = denied.get(who);
        if (theirs == null) {
            return;
        }
        theirs.remove(node);
        if (theirs.isEmpty()) {
            denied.remove(who, theirs);
        }
    }
}
