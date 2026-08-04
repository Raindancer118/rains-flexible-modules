package de.raindancer.modules.tpa.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What one player has decided about being asked.
 *
 * <h2>Two switches, not one</h2>
 * A blanket "nobody may ask me" and a list of particular people are different needs: somebody building
 * something wants an hour's quiet from everybody, and somebody being pestered wants one person gone for
 * good. Folding them together would mean the second could only be had by taking the first — so turning
 * the blanket switch back on deliberately does not forget who was blocked.
 *
 * <h2>Why it is a value</h2>
 * It is held in a concurrent map and read from several threads. A prefs object that could be changed
 * under a reader is a block list that is briefly empty while somebody is asking, which is a bug nobody
 * would ever reproduce on purpose.
 */
public record TpaPrefs(boolean accepting, Set<UUID> blocked) {

    public TpaPrefs {
        blocked = blocked == null ? Set.of() : Set.copyOf(blocked);
    }

    /**
     * Somebody who has decided nothing: open to everybody, with nobody blocked.
     *
     * <p>Not called {@code accepting()} — a record cannot have a static method of the same name as one
     * of its components, and {@code accepting} is one.
     */
    public static TpaPrefs untouched() {
        return new TpaPrefs(true, Set.of());
    }

    /**
     * Whether this person may ask them.
     *
     * <p>The two switches, in one answer — and the caller says the same thing whichever of them
     * refused. Telling somebody they have been blocked turns a quiet decision into a confrontation;
     * "not accepting requests right now" is what everybody sees, and it is true.
     */
    public boolean mayBeAskedBy(UUID asker) {
        return asker != null && accepting && !blocked.contains(asker);
    }

    public boolean hasBlocked(UUID who) {
        return who != null && blocked.contains(who);
    }

    // ------------------------------------------------------------------------ changing

    public TpaPrefs acceptingEverybody() {
        return new TpaPrefs(true, blocked);
    }

    public TpaPrefs refusingEverybody() {
        return new TpaPrefs(false, blocked);
    }

    public TpaPrefs blocking(UUID who) {
        if (who == null || blocked.contains(who)) {
            return this;
        }
        Set<UUID> now = new LinkedHashSet<>(blocked);
        now.add(who);
        return new TpaPrefs(accepting, now);
    }

    public TpaPrefs unblocking(UUID who) {
        if (who == null || !blocked.contains(who)) {
            return this;
        }
        Set<UUID> now = new LinkedHashSet<>(blocked);
        now.remove(who);
        return new TpaPrefs(accepting, now);
    }

    /**
     * Whether this is worth a line on disk.
     *
     * <p>Somebody who has changed nothing is not: a server would otherwise keep one entry per person
     * who has ever used the plugin, and every one of them would say nothing at all. The old plugin
     * pruned these on write for the same reason.
     */
    public boolean isWorthKeeping() {
        return !accepting || !blocked.isEmpty();
    }
}
