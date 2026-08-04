package de.raindancer.modules.warp.store;

import de.raindancer.core.world.warp.Warp;
import de.raindancer.core.world.warp.Warps;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The module's door to the warps, which are RainsCore's.
 *
 * <h2>Why there is no store of its own</h2>
 * Because a warp is a place with a name, a world and coordinates, and RainsCore already keeps those
 * — persistence, atomic writes, worlds that are not loaded and "is this reachable" are solved there
 * and tested there. A second store that happened to look the same would mean a ghast line could not
 * fly to a warp, a menu could not list warps beside homes, and deleting a world would leave its
 * warps behind pointing at nothing.
 *
 * <p>So what is here is the two things the module adds on top: the {@link WarpAccess} reading of the
 * permission Core keeps, and writing straight through to disk.
 *
 * <h2>Why every change flushes</h2>
 * Because these are access decisions. A warp made staff-only now and public again after the next
 * restart is a hole found by somebody walking into the staff room, and by then nobody remembers
 * which restart it was.
 */
public final class WarpCatalogue {

    private final Warps warps;
    /** Writing the places out. Core's {@code PoiStore::flush}, behind an interface for the tests. */
    private final Runnable flush;

    public WarpCatalogue(Warps warps, Runnable flush) {
        this.warps = warps;
        this.flush = flush == null ? () -> {
        } : flush;
    }

    // ------------------------------------------------------------------------ looking

    public List<Warp> all() {
        return warps.all();
    }

    public Optional<Warp> byName(String name) {
        return warps.byName(name);
    }

    public int count() {
        return warps.all().size();
    }

    /** What the permission on a warp means. */
    public WarpAccess accessOf(Warp warp) {
        return warp == null ? WarpAccess.EVERYONE : WarpAccess.from(warp.permission().orElse(null));
    }

    /**
     * The warps this player is shown, in alphabetical order.
     *
     * <p>Filtered by the rule rather than by Core's own {@code visibleTo}, because the rule is where
     * "an admin sees everything" lives and Core has no idea what an admin of this module is.
     */
    public List<Warp> visibleTo(Predicate<String> hasPermission, WarpAccessRule rule) {
        return warps.all().stream()
                .filter(warp -> rule.maySee(accessOf(warp), hasPermission))
                .sorted(Comparator.comparing(Warp::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The warps in one category that this player is shown.
     *
     * @param category null gives the ones filed under nothing
     */
    public List<Warp> inCategory(String category, Predicate<String> hasPermission,
                                 WarpAccessRule rule) {
        return visibleTo(hasPermission, rule).stream()
                .filter(warp -> category == null
                        ? warp.category().isEmpty()
                        : warp.category().map(category::equalsIgnoreCase).orElse(false))
                .toList();
    }

    /**
     * The categories this player would find something in.
     *
     * <p>Only the ones with a warp they can see: a category page listing "Staff" with nothing behind
     * it tells an ordinary player exactly what they were not meant to be told.
     */
    public Set<String> categoriesVisibleTo(Predicate<String> hasPermission, WarpAccessRule rule) {
        Set<String> found = new LinkedHashSet<>();
        for (Warp warp : visibleTo(hasPermission, rule)) {
            warp.category().ifPresent(found::add);
        }
        return found;
    }

    /** Whether any visible warp is filed under nothing, so the menu knows to offer that page. */
    public boolean hasUncategorised(Predicate<String> hasPermission, WarpAccessRule rule) {
        return visibleTo(hasPermission, rule).stream().anyMatch(warp -> warp.category().isEmpty());
    }

    // ------------------------------------------------------------------------ changing

    /** Makes one where somebody is standing. */
    public Optional<Warp> create(String name, Location where, UUID creator) {
        Optional<Warp> made = warps.create(name, where, creator);
        made.ifPresent(ignored -> flush.run());
        return made;
    }

    /** Moves one, keeping its access, its category and its icon — see {@code Warps.move}. */
    public boolean move(String name, Location where) {
        return written(warps.move(name, where));
    }

    public boolean delete(String name) {
        return written(warps.delete(name));
    }

    /** Who a warp is for. */
    public boolean setAccess(String name, WarpAccess access) {
        if (access == null) {
            return false;
        }
        return written(warps.setPermission(name, access.permission().orElse(null)));
    }

    /** What it is filed under; null takes it out of every category. */
    public boolean setCategory(String name, String category) {
        return written(warps.setCategory(name, category));
    }

    /** What a menu calls it; null puts it back to being called by its name. */
    public boolean setLabel(String name, String label) {
        return written(warps.setLabel(name, label));
    }

    public boolean setIcon(String name, Material icon) {
        return written(warps.setIcon(name, icon));
    }

    private boolean written(boolean changed) {
        if (changed) {
            flush.run();
        }
        return changed;
    }
}
